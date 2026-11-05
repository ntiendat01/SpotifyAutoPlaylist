package com.spotifyautoplaylist.spotify.rules

/**
 * TASK-055: minimum-listen gate for qualification counting.
 *
 * Spotify Recently Played exposes `played_at` (end of play) and track `duration_ms`, not listened
 * duration. For consecutive plays P (after Q) in chronological order of the stored history (any
 * track), `gap = P.played_at - Q.played_at` is an upper bound on how long P was listened (pauses
 * only lengthen the gap). Therefore `gap < threshold` means P was **definitely** listened for less
 * than the threshold and must not count toward BR-001/BR-002.
 *
 * Threshold is `min(30s, track duration)` when duration is known and positive; otherwise 30s.
 * The oldest play in the ordered history has no predecessor and always counts.
 *
 * Applied inside [QualificationEvaluator] so every re-evaluation over stored Room events is
 * deterministic and idempotent. History sync still stores every valid play (dedupe unchanged);
 * already-persisted qualifications/outbox rows are never removed (BR-006).
 */
object MinListenFilter {
    const val MIN_LISTEN_MS = 30_000L

    /** Effective gap threshold for one play. Unknown/non-positive duration → full 30s. */
    fun thresholdMs(durationMs: Long?): Long {
        if (durationMs == null || durationMs <= 0L) return MIN_LISTEN_MS
        return minOf(MIN_LISTEN_MS, durationMs)
    }

    /**
     * Returns the subset of [events] that count toward daily/rolling rules.
     * Deduplicates by `(userId, trackId, playedAtMs)` first (BR-004), then walks chronological
     * order (tie-break `trackId`). Predecessor is the previous play in that full history, whether
     * or not that predecessor itself counted.
     */
    fun countable(events: Iterable<PlayRecord>): List<PlayRecord> {
        val ordered = events.asSequence()
            .distinctBy { Triple(it.userId, it.trackId, it.playedAtMs) }
            .sortedWith(compareBy({ it.playedAtMs }, { it.trackId }, { it.userId }))
            .toList()
        if (ordered.isEmpty()) return emptyList()
        val out = ArrayList<PlayRecord>(ordered.size)
        out.add(ordered[0])
        for (i in 1 until ordered.size) {
            val p = ordered[i]
            val q = ordered[i - 1]
            val gap = p.playedAtMs - q.playedAtMs
            if (gap >= thresholdMs(p.durationMs)) out.add(p)
        }
        return out
    }
}
