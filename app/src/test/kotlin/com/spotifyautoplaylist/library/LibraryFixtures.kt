package com.spotifyautoplaylist.library

import com.spotifyautoplaylist.data.local.*
import com.spotifyautoplaylist.data.sync.SyncRunStatus
import java.time.ZonedDateTime
import java.time.ZoneId

/** Synthetic, neutral test rows only ("Bài thử A"); never PNG example artists or metrics. */
object LibraryFixtures {
    const val USER = "user-test"
    const val PLAYLIST = "PLAYLISTID000000000001"
    const val H = 3_600_000L
    val NOW: Long = ZonedDateTime.of(2026, 11, 5, 12, 0, 0, 0, ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toEpochMilli()

    fun id(n: Int) = "TRACK" + n.toString().padStart(17, '0')
    fun track(n: Int, name: String = "Bài thử ${'A' + n}") = TrackEntity(USER, id(n), "spotify:track:${id(n)}", name, "Nghệ sĩ thử $n", "Album thử", null)
    /**
     * Play times are staggered by track index (≥35s) so TASK-055 min-listen gaps stay valid when several
     * tracks share the same hour-ago bucket (cross-track predecessor rule).
     */
    fun plays(n: Int, vararg hoursAgo: Number) = hoursAgo.map {
        PlayEventEntity(USER, id(n), NOW - (it.toDouble() * H).toLong() - n * 35_000L, NOW)
    }
    fun outbox(n: Int, state: OutboxState, addedAt: Long? = null, playlist: String = PLAYLIST, error: String? = null, attempts: Int = 0) =
        PlaylistOutboxEntity(USER, playlist, id(n), state, attempts, NOW - H, addedAt, error)
    fun qual(n: Int, by: QualifiedBy = QualifiedBy.DAILY) = QualificationEntity(USER, id(n), NOW - 2 * H, by)
    val playlist = ManagedPlaylistEntity(USER, PLAYLIST, null, "Replay thử", NOW - 100 * H, true)

    fun rows(
        tracks: List<TrackEntity> = emptyList(), events: List<PlayEventEntity> = emptyList(),
        quals: List<QualificationEntity> = emptyList(), outbox: List<PlaylistOutboxEntity> = emptyList(),
        playlist: ManagedPlaylistEntity? = this.playlist, coverage: CoverageStatus = CoverageStatus.COMPLETE_SINCE_BASELINE,
        status: SyncRunStatus = SyncRunStatus(outcome = "SUCCESS", finishedAtMs = NOW - H, lastSuccessAtMs = NOW - H),
        daily: Int = 3, rolling: Int = 5,
    ) = LibraryRows(tracks, events, quals, outbox, playlist, SyncStateEntity(USER, coverageStatus = coverage), daily, rolling, status)

    /** A mixed account used by the screen tests. */
    fun mixed() = rows(
        tracks = (0..6).map { track(it) },
        events = plays(0, 1, 2) + plays(1, 30, 50, 60, 70) + plays(2, 1, 2, 3) + plays(3, 1, 2, 3, 4) + plays(4, 5, 6, 7) +
            plays(5, 100) + plays(6, 1, 2, 3),
        quals = listOf(qual(2), qual(3), qual(4), qual(6)),
        outbox = listOf(outbox(2, OutboxState.PRESENT, NOW - H), outbox(3, OutboxState.PENDING),
            outbox(4, OutboxState.BLOCKED, error = "FORBIDDEN"), outbox(6, OutboxState.VERIFY_REMOTE)),
    )
}
