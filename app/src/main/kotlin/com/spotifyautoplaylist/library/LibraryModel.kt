package com.spotifyautoplaylist.library

import com.spotifyautoplaylist.data.local.CoverageStatus
import com.spotifyautoplaylist.data.local.ManagedPlaylistEntity
import com.spotifyautoplaylist.data.local.OutboxState
import com.spotifyautoplaylist.data.local.PlayEventEntity
import com.spotifyautoplaylist.data.local.PlaylistOutboxEntity
import com.spotifyautoplaylist.data.local.QualificationEntity
import com.spotifyautoplaylist.data.local.QualifiedBy
import com.spotifyautoplaylist.data.local.SyncStateEntity
import com.spotifyautoplaylist.data.local.TrackEntity
import com.spotifyautoplaylist.data.sync.SyncRunStatus
import com.spotifyautoplaylist.spotify.rules.QualificationEvaluator
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * TASK-050C read model. Everything the screens show is derived here from one account's Room rows (pure,
 * JVM-tested). No counting or time-zone logic lives in composables (06 §10).
 */
data class LibraryRows(
    val tracks: List<TrackEntity>,
    val events: List<PlayEventEntity>,
    val qualifications: List<QualificationEntity>,
    val outbox: List<PlaylistOutboxEntity>,
    val playlist: ManagedPlaylistEntity?,
    val syncState: SyncStateEntity?,
    val dailyThreshold: Int,
    val rollingThreshold: Int,
    val status: SyncRunStatus,
)

/** 05 §UI status priority, highest first. */
enum class TrackStatus(val label: String) {
    ADDED("Đã thêm vào Spotify"),
    VERIFYING("Đang xác minh trên Spotify"),
    ADDING("Đủ điều kiện · Chờ thêm"),
    NEEDS_REVIEW("Cần kiểm tra"),
    REMOVED_EXTERNALLY("Đã gỡ khỏi playlist trên Spotify"),
    QUALIFIED("Đủ điều kiện"),
    NEAR("Gần đạt"),
    TRACKING("Đang theo dõi"),
}

enum class Coverage { COMPLETE_SINCE_BASELINE, PARTIAL, UNKNOWN }

data class TrackUiModel(
    val trackId: String,
    val name: String,
    val artists: String,
    val album: String?,
    val todayCount: Int,
    val rolling72hCount: Int,
    val qualifiedBy: QualifiedBy?,
    val qualifiedAtMs: Long?,
    val status: TrackStatus,
    val lastPlayedAtMs: Long?,
    val playTimesMs: List<Long>,
    val addedAtMs: Long?,
    val lastAttemptMs: Long?,
    val attemptCount: Int,
    val errorCode: String? = null,
    val spotifyUrl: String?,
) {
    /** Missing plays to the nearer threshold; 0 when either is reached. */
    fun remaining(daily: Int, rolling: Int) = minOf(daily - todayCount, rolling - rolling72hCount).coerceAtLeast(0)
}

data class PlaylistUi(val name: String, val url: String?, val verified: Boolean)

data class DayBucket(val day: LocalDate, val label: String, val count: Int)

data class LibrarySnapshot(
    val nowMs: Long,
    val tracks: List<TrackUiModel>,
    val playlist: PlaylistUi?,
    /** Remotely confirmed (PRESENT) tracks on the current playlist; null = never verified, not zero. */
    val confirmedCount: Int?,
    val coverage: Coverage,
    /** Last fully successful pass (remote playlist reconciled); null = never. */
    val lastVerifiedAtMs: Long?,
    val dailyThreshold: Int,
    val rollingThreshold: Int,
    val status: SyncRunStatus,
    val days: List<DayBucket>,
)

object LibraryModel {
    private val ZONE = QualificationEvaluator.ZONE
    private const val WINDOW = QualificationEvaluator.ROLLING_WINDOW_MS
    private val WEEKDAY = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

    fun build(rows: LibraryRows, nowMs: Long): LibrarySnapshot {
        val today = day(nowMs)
        val currentId = rows.playlist?.playlistId
        val outbox = rows.outbox.filter { it.playlistId == currentId }.associateBy { it.trackId }
        val quals = rows.qualifications.associateBy { it.trackId }
        val times = rows.events.groupBy({ it.trackId }, { it.playedAtMs }).mapValues { it.value.distinct().sortedDescending() }
        val tracks = rows.tracks.map { t ->
            val played = times[t.trackId].orEmpty()
            val todayCount = played.count { it <= nowMs && day(it) == today }
            val rolling = played.count { it <= nowMs && it > nowMs - WINDOW }
            val q = quals[t.trackId]
            val row = outbox[t.trackId]
            val near = q == null && (todayCount == rows.dailyThreshold - 1 || rolling == rows.rollingThreshold - 1)
            TrackUiModel(
                trackId = t.trackId, name = t.name, artists = t.artistNames, album = t.albumName,
                todayCount = todayCount, rolling72hCount = rolling,
                qualifiedBy = q?.qualifiedBy, qualifiedAtMs = q?.qualifiedAtMs,
                status = status(row?.state, q != null, near),
                lastPlayedAtMs = played.firstOrNull(), playTimesMs = played,
                addedAtMs = row?.addedAtMs?.takeIf { row.state == OutboxState.PRESENT },
                lastAttemptMs = row?.lastAttemptMs, attemptCount = row?.attemptCount ?: 0, errorCode = row?.lastErrorCode,
                spotifyUrl = trackUrl(t.uri),
            )
        }.sortedWith(compareByDescending<TrackUiModel> { it.lastPlayedAtMs ?: Long.MIN_VALUE }.thenBy { it.trackId })
        val present = outbox.values.count { it.state == OutboxState.PRESENT }
        // No per-row verification column exists: a SUCCESS pass reconciled the whole remote playlist (TASK-041).
        val verifiedAt = rows.status.lastSuccessAtMs.takeIf { rows.playlist != null }
        val coverage = when (rows.syncState?.coverageStatus) {
            CoverageStatus.COMPLETE_SINCE_BASELINE -> Coverage.COMPLETE_SINCE_BASELINE
            CoverageStatus.PARTIAL_BOOTSTRAP, CoverageStatus.HISTORY_INCOMPLETE -> Coverage.PARTIAL
            else -> Coverage.UNKNOWN
        }
        return LibrarySnapshot(
            nowMs = nowMs, tracks = tracks,
            playlist = rows.playlist?.let { PlaylistUi(it.name, it.playlistUrl ?: playlistUrl(it.playlistId), it.ownershipVerified) },
            confirmedCount = if (rows.playlist != null && (present > 0 || verifiedAt != null)) present else null,
            coverage = coverage, lastVerifiedAtMs = verifiedAt,
            dailyThreshold = rows.dailyThreshold, rollingThreshold = rows.rollingThreshold,
            status = rows.status, days = days(rows.events, nowMs),
        )
    }

    private fun status(state: OutboxState?, qualified: Boolean, near: Boolean) = when (state) {
        OutboxState.PRESENT -> TrackStatus.ADDED
        OutboxState.VERIFY_REMOTE, OutboxState.CHECKING_REMOTE -> TrackStatus.VERIFYING
        OutboxState.PENDING, OutboxState.SENDING, OutboxState.RETRY_LATER -> TrackStatus.ADDING
        OutboxState.BLOCKED -> TrackStatus.NEEDS_REVIEW
        OutboxState.REMOVED_EXTERNALLY -> TrackStatus.REMOVED_EXTERNALLY
        null -> when {
            qualified -> TrackStatus.QUALIFIED
            near -> TrackStatus.NEAR
            else -> TrackStatus.TRACKING
        }
    }

    /** Seven Vietnam calendar days ending today; deduplicated events, not distinct songs. */
    fun days(events: List<PlayEventEntity>, nowMs: Long): List<DayBucket> {
        val today = day(nowMs)
        val counts = events.asSequence().filter { it.playedAtMs <= nowMs }.distinctBy { it.trackId to it.playedAtMs }
            .groupingBy { day(it.playedAtMs) }.eachCount()
        return (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            DayBucket(d, WEEKDAY[d.dayOfWeek.value - 1], counts[d] ?: 0)
        }
    }

    /** Almost There: unqualified, not on Spotify, played in the window; closest first, then most recent. */
    fun almostThere(s: LibrarySnapshot, limit: Int = 3) = s.tracks
        .filter { it.qualifiedBy == null && it.status in setOf(TrackStatus.NEAR, TrackStatus.TRACKING) && it.rolling72hCount > 0 }
        .sortedWith(compareBy<TrackUiModel> { it.remaining(s.dailyThreshold, s.rollingThreshold) }
            .thenByDescending { it.lastPlayedAtMs ?: 0 })
        .take(limit)

    /** Newly Collected: remotely confirmed only, latest addition first. */
    fun newlyCollected(s: LibrarySnapshot, limit: Int = 5) = s.tracks
        .filter { it.status == TrackStatus.ADDED }
        .sortedByDescending { it.addedAtMs ?: 0 }
        .take(limit)

    enum class TimeFilter(val label: String) { TODAY("Hôm nay"), H72("72 giờ"), ALL("Tất cả") }
    enum class StatusFilter(val label: String) { ALL("Tất cả"), NEAR("Gần đạt"), ADDED("Đã thêm") }

    /** History list (06 §5): Room data only, one row per track, latest play first. Accent/case-insensitive search. */
    fun history(s: LibrarySnapshot, query: String, time: TimeFilter, status: StatusFilter): List<TrackUiModel> {
        val q = fold(query.trim())
        return s.tracks.filter { t ->
            when (time) { TimeFilter.TODAY -> t.todayCount > 0; TimeFilter.H72 -> t.rolling72hCount > 0; TimeFilter.ALL -> t.playTimesMs.isNotEmpty() }
        }.filter { t ->
            when (status) { StatusFilter.ALL -> true; StatusFilter.NEAR -> t.status == TrackStatus.NEAR; StatusFilter.ADDED -> t.status == TrackStatus.ADDED }
        }.filter { q.isEmpty() || fold(it.name).contains(q) || fold(it.artists).contains(q) }
    }

    /** Plays counted by [history] rows for the chosen window (summary line). */
    fun plays(rows: List<TrackUiModel>, time: TimeFilter) = rows.sumOf {
        when (time) { TimeFilter.TODAY -> it.todayCount; TimeFilter.H72 -> it.rolling72hCount; TimeFilter.ALL -> it.playTimesMs.size }
    }

    private val MARKS = Regex("\\p{Mn}+")
    internal fun fold(text: String): String = MARKS.replace(java.text.Normalizer.normalize(text.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD), "")
        .replace('đ', 'd')

    enum class Segment(val label: String) { CONFIRMED("Đã thêm"), PENDING("Đang chờ"), REVIEW("Cần kiểm tra") }

    /** Collection (06 §6): strict segregation; only PRESENT is "Đã thêm". */
    fun collection(s: LibrarySnapshot, segment: Segment): List<TrackUiModel> = when (segment) {
        Segment.CONFIRMED -> newlyCollected(s, Int.MAX_VALUE)
        Segment.PENDING -> s.tracks.filter { it.status in setOf(TrackStatus.ADDING, TrackStatus.VERIFYING, TrackStatus.QUALIFIED) }
            .sortedByDescending { it.qualifiedAtMs ?: 0 }
        Segment.REVIEW -> s.tracks.filter { it.status == TrackStatus.NEEDS_REVIEW || it.status == TrackStatus.REMOVED_EXTERNALLY }
    }

    /** Short reason for a pending / review row; codes only, never raw messages. */
    fun reason(t: TrackUiModel, nowMs: Long): String = when (t.status) {
        TrackStatus.REMOVED_EXTERNALLY -> "Bài đã bị gỡ khỏi playlist trên Spotify. Ứng dụng sẽ không tự thêm lại."
        TrackStatus.NEEDS_REVIEW -> if (t.errorCode == "ATTEMPTS_EXHAUSTED")
            "Đã thử ${t.attemptCount} lần nhưng chưa xác minh được trên Spotify. Ứng dụng tạm dừng thêm bài này."
            else "Không thể thêm bài tự động. Cần kiểm tra."
        TrackStatus.VERIFYING -> "Đang chờ kiểm tra playlist trên Spotify"
        TrackStatus.ADDING -> if (t.attemptCount > 0 && t.lastAttemptMs != null)
            "Sẽ thử lại · ${t.attemptCount} lần thử · lần cuối ${time(t.lastAttemptMs, nowMs)}" else "Chờ lần đồng bộ tiếp theo"
        TrackStatus.QUALIFIED -> "Đủ điều kiện · Chờ thêm vào playlist"
        else -> t.status.label
    }

    fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDate()

    private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE)
    private val DATE = DateTimeFormatter.ofPattern("dd/MM").withZone(ZONE)
    private val FULL = DateTimeFormatter.ofPattern("HH:mm:ss · dd/MM/yyyy").withZone(ZONE)

    /** "HH:mm" today, otherwise "HH:mm dd/MM", Vietnam time. */
    fun time(ms: Long, nowMs: Long): String =
        if (day(ms) == day(nowMs)) TIME.format(Instant.ofEpochMilli(ms)) else "${TIME.format(Instant.ofEpochMilli(ms))} ${DATE.format(Instant.ofEpochMilli(ms))}"

    fun date(ms: Long): String = DATE.format(Instant.ofEpochMilli(ms))
    fun fullTime(ms: Long): String = FULL.format(Instant.ofEpochMilli(ms))

    private val ID = Regex("[A-Za-z0-9]{22}")
    internal fun trackUrl(uri: String): String? =
        uri.removePrefix("spotify:track:").takeIf { uri.startsWith("spotify:track:") && ID.matches(it) }?.let { "https://open.spotify.com/track/$it" }
    private fun playlistUrl(id: String): String? = id.takeIf { ID.matches(it) }?.let { "https://open.spotify.com/playlist/$it" }
}

/** User-facing sync copy (06 §9). Only codes, never raw messages, reach the UI. */
object SyncCopy {
    fun headline(status: SyncRunStatus, nowMs: Long): String = when (status.outcome) {
        null -> "Chưa đồng bộ lần nào"
        "SUCCESS" -> "Đã đồng bộ lúc ${LibraryModel.time(status.finishedAtMs ?: nowMs, nowMs)}"
        "PARTIAL" -> "Đồng bộ một phần lúc ${LibraryModel.time(status.finishedAtMs ?: nowMs, nowMs)}"
        "NETWORK_ERROR" -> "Không có mạng · Hiển thị dữ liệu trên máy"
        "AUTH_REQUIRED" -> "Cần đăng nhập lại Spotify"
        "ACCOUNT_MISMATCH", "ACCOUNT_CHANGED" -> "Tài khoản Spotify không khớp với tài khoản đã thiết lập."
        "RELINK_REQUIRED" -> "Playlist cần liên kết lại"
        "FORBIDDEN" -> "Spotify từ chối quyền truy cập"
        "RATE_LIMITED" -> "Spotify đang giới hạn yêu cầu. Sẽ thử lại."
        "QUOTA_EXCEEDED" -> "Đã hết hạn mức Spotify API. Tạm dừng đồng bộ."
        "DISABLED" -> "Tự động đồng bộ đang tắt"
        "DEFERRED" -> "Đồng bộ tạm hoãn"
        "NO_ACCOUNT" -> "Chưa kết nối Spotify"
        else -> "Trạng thái đồng bộ chưa xác định"
    }

    fun isProblem(status: SyncRunStatus) = status.outcome != null && status.outcome !in setOf("SUCCESS", "PARTIAL", "DISABLED", "DEFERRED")
}
