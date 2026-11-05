package com.spotifyautoplaylist.spotify.sync

import com.spotifyautoplaylist.auth.SpotifyResourceId
import com.spotifyautoplaylist.spotify.PlayHistoryItem
import com.spotifyautoplaylist.spotify.RecentlyPlayedPage
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyClient
import java.time.Instant
import java.time.format.DateTimeParseException

/** Mirrors `sync_state.coverage_status` (04 §6, 05 §1). */
enum class Coverage { UNKNOWN, COMPLETE_SINCE_BASELINE, PARTIAL_BOOTSTRAP, HISTORY_INCOMPLETE }

/** Persisted sync checkpoint for one account (`sync_state`). [lastPageCursor] is progress, not proof of coverage. */
data class SyncCheckpoint(
    val highWaterPlayedAtMs: Long? = null,
    val coverage: Coverage = Coverage.UNKNOWN,
    val lastSuccessAtMs: Long? = null,
    val lastAttemptAtMs: Long? = null,
    val lastErrorCode: String? = null,
    val lastPageCursor: String? = null,
    val gapStartMs: Long? = null,
    val gapEndMs: Long? = null,
)

/** One valid canonical play event (02 "Canonical event") with the track metadata to upsert. */
data class ValidPlay(
    val trackId: String,
    val playedAtMs: Long,
    val uri: String,
    val name: String,
    val artistNames: String,
    val albumName: String?,
    val imageUrl: String?,
)

/** Persistence port. Implementations must make [importPage] one transaction (TX-01). */
interface HistoryStore {
    suspend fun checkpoint(userId: String): SyncCheckpoint?

    /** TX-01: upsert tracks, insert-or-ignore events and save [progress] atomically. Returns newly inserted events. */
    suspend fun importPage(userId: String, plays: List<ValidPlay>, progress: SyncCheckpoint, ingestedAtMs: Long): Int

    /** TX-02: final checkpoint (high-water, coverage, gap); called only after all pages of the run were imported. */
    suspend fun commit(userId: String, checkpoint: SyncCheckpoint)
}

/** API-01 page source; [before] is epoch ms, null for the newest page. */
fun interface RecentlyPlayedSource {
    suspend fun page(before: Long?): RecentlyPlayedPage
}

fun SpotifyClient.asRecentlyPlayedSource(): RecentlyPlayedSource =
    RecentlyPlayedSource { before -> recentlyPlayed(before = before) }

enum class StopReason {
    /** An item at or older than the previous high-water was seen (overlap inclusive). */
    BRIDGED,
    /** Empty page, `next == null` or no usable `before` cursor. */
    END_OF_HISTORY,
    PAGE_CAP,
    /** Cursor did not move strictly backwards. */
    NO_PROGRESS,
}

data class SyncResult(
    val pages: Int,
    val inserted: Int,
    val filtered: Int,
    val stop: StopReason,
    val checkpoint: SyncCheckpoint,
)

/**
 * TASK-030: newest-page backward pagination with an inclusive overlap on the committed high-water
 * (03 §4, 04 §6, 05 TX-01/TX-02). Every page is persisted before the next request; the high-water only
 * moves in the final commit and only when the run bridged the previous high-water or established a
 * first baseline. Otherwise the old high-water is kept and the gap is recorded as HISTORY_INCOMPLETE.
 * No plays are inferred. Callers must serialize runs per account (TASK-051).
 */
class HistorySync(
    private val source: RecentlyPlayedSource,
    private val store: HistoryStore,
    private val clock: () -> Instant,
    private val pageCap: Int = DEFAULT_PAGE_CAP,
) {
    init { require(pageCap >= 1) }

    suspend fun sync(userId: String): SyncResult {
        require(userId.isNotBlank())
        val now = clock().toEpochMilli()
        val previous = store.checkpoint(userId) ?: SyncCheckpoint()
        val oldHighWater = previous.highWaterPlayedAtMs
        var progress = previous.copy(lastAttemptAtMs = now)
        var before: Long? = null
        var pages = 0
        var inserted = 0
        var filtered = 0
        var newest: Long? = null
        var oldest: Long? = null
        val stop: StopReason
        try {
            while (true) {
                if (pages >= pageCap) { stop = StopReason.PAGE_CAP; break }
                val page = source.page(before)
                pages++
                val parsed = page.items.map { parse(it, now) }
                val times = parsed.mapNotNull { it.playedAtMs }
                val plays = parsed.mapNotNull { it.play }
                filtered += parsed.size - plays.size
                times.maxOrNull()?.let { t -> newest = maxOf(newest ?: t, t) }
                times.minOrNull()?.let { t -> oldest = minOf(oldest ?: t, t) }
                val cursor = nextBefore(page, times)
                progress = progress.copy(lastPageCursor = cursor?.toString())
                if (page.items.isNotEmpty()) {
                    inserted += store.importPage(userId, plays, progress, now)
                }
                if (oldHighWater != null && times.any { it <= oldHighWater }) { stop = StopReason.BRIDGED; break }
                if (page.items.isEmpty() || page.next == null || cursor == null) { stop = StopReason.END_OF_HISTORY; break }
                if (before != null && cursor >= before) { stop = StopReason.NO_PROGRESS; break }
                before = cursor
            }
        } catch (e: SpotifyApiException) {
            // Committed pages stay; the high-water does not move, so the next run must still bridge it.
            store.commit(userId, failed(progress, oldHighWater, oldest, now, e.kind.name))
            throw e
        }
        val final = finish(progress, oldHighWater, newest, oldest, stop, now)
        store.commit(userId, final)
        return SyncResult(pages, inserted, filtered, stop, final)
    }

    private fun finish(p: SyncCheckpoint, oldHw: Long?, newest: Long?, oldest: Long?, stop: StopReason, now: Long): SyncCheckpoint {
        val base = p.copy(lastSuccessAtMs = now, lastErrorCode = null, lastPageCursor = null)
        if (oldHw == null) {
            // First baseline. No events at all gives no evidence of anything.
            if (newest == null) return base.copy(coverage = Coverage.UNKNOWN)
            val coverage = if (stop == StopReason.END_OF_HISTORY) Coverage.COMPLETE_SINCE_BASELINE else Coverage.PARTIAL_BOOTSTRAP
            return base.copy(highWaterPlayedAtMs = newest, coverage = coverage, gapStartMs = null, gapEndMs = null)
        }
        if (stop == StopReason.BRIDGED) {
            // Contiguous from the retained high-water to now. After a recorded gap we cannot tell whether the
            // original baseline was complete, so the restored status is the conservative PARTIAL_BOOTSTRAP.
            val coverage = when (p.coverage) {
                Coverage.COMPLETE_SINCE_BASELINE -> Coverage.COMPLETE_SINCE_BASELINE
                else -> Coverage.PARTIAL_BOOTSTRAP
            }
            return base.copy(highWaterPlayedAtMs = maxOf(oldHw, newest ?: oldHw), coverage = coverage, gapStartMs = null, gapEndMs = null)
        }
        // Could not bridge: keep the old high-water (TX-02) and expose the gap.
        return base.copy(coverage = Coverage.HISTORY_INCOMPLETE, gapStartMs = oldHw, gapEndMs = oldest ?: now)
    }

    private fun failed(p: SyncCheckpoint, oldHw: Long?, oldest: Long?, now: Long, code: String): SyncCheckpoint =
        if (oldHw == null) p.copy(lastErrorCode = code, lastPageCursor = null)
        else p.copy(lastErrorCode = code, lastPageCursor = null, coverage = Coverage.HISTORY_INCOMPLETE,
            gapStartMs = oldHw, gapEndMs = oldest ?: now)

    private class Parsed(val playedAtMs: Long?, val play: ValidPlay?)

    private fun parse(item: PlayHistoryItem, now: Long): Parsed {
        val time = try { Instant.parse(item.playedAt).toEpochMilli() } catch (_: DateTimeParseException) { null }
            ?.takeIf { it in 0..(now + FUTURE_TOLERANCE_MS) }
            ?: return Parsed(null, null) // BR-012: invalid or future timestamps are rejected, never counted.
        val track = item.track
        val id = track?.id
        if (track == null || id == null || !SpotifyResourceId.isId(id) || track.type != "track" || track.isLocal == true) {
            return Parsed(time, null) // TC-027: local files, episodes, null/invalid IDs are filtered.
        }
        return Parsed(time, ValidPlay(
            trackId = id,
            playedAtMs = time,
            uri = "spotify:track:$id",
            name = track.name.orEmpty(),
            artistNames = track.artists.mapNotNull { it.name }.joinToString(", "),
            albumName = track.album?.name,
            imageUrl = track.album?.images?.firstOrNull()?.url,
        ))
    }

    /** Spotify's `cursors.before` (epoch ms); falls back to the oldest item on the page. */
    private fun nextBefore(page: RecentlyPlayedPage, times: List<Long>): Long? =
        page.cursors?.before?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: times.minOrNull()

    companion object {
        const val DEFAULT_PAGE_CAP = 20
        const val FUTURE_TOLERANCE_MS = 5 * 60 * 1000L
    }
}
