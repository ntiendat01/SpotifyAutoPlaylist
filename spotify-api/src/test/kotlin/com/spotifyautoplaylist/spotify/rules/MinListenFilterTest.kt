package com.spotifyautoplaylist.spotify.rules

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinListenFilterTest {
    private fun vn(text: String) = LocalDateTime.parse(text).atZone(QualificationEvaluator.ZONE).toInstant().toEpochMilli()
    private fun ev(ms: Long, track: String = A, durationMs: Long? = null, user: String = USER) =
        PlayRecord(user, track, ms, durationMs)

    private val t0 = vn("2026-11-04T10:00:00")
    private val sec = 1_000L
    private val min30 = MinListenFilter.MIN_LISTEN_MS

    @Test fun gapStrictlyUnder30sIsExcluded() {
        val kept = MinListenFilter.countable(listOf(ev(t0), ev(t0 + min30 - 1)))
        assertEquals(listOf(ev(t0)), kept)
    }

    @Test fun gapExactly30sCounts() {
        val second = ev(t0 + min30)
        val kept = MinListenFilter.countable(listOf(ev(t0), second))
        assertEquals(listOf(ev(t0), second), kept)
    }

    @Test fun firstOfBatchAlwaysCounts() {
        assertEquals(listOf(ev(t0)), MinListenFilter.countable(listOf(ev(t0))))
        // Even a lone short-track play counts (no predecessor to prove a short listen).
        assertEquals(listOf(ev(t0, durationMs = 5_000)), MinListenFilter.countable(listOf(ev(t0, durationMs = 5_000))))
    }

    @Test fun shortTrackUsesMinOf30sAndDuration() {
        val duration = 15_000L
        // gap 10s < 15s → exclude
        assertEquals(listOf(ev(t0)), MinListenFilter.countable(listOf(ev(t0), ev(t0 + 10_000, durationMs = duration))))
        // gap 15s >= 15s → count
        val ok = ev(t0 + 15_000, durationMs = duration)
        assertEquals(listOf(ev(t0), ok), MinListenFilter.countable(listOf(ev(t0), ok)))
        // gap 20s would fail a flat 30s rule but passes short-track threshold
        val mid = ev(t0 + 20_000, durationMs = duration)
        assertEquals(listOf(ev(t0), mid), MinListenFilter.countable(listOf(ev(t0), mid)))
    }

    @Test fun unknownOrNonPositiveDurationFallsBackTo30s() {
        assertEquals(MinListenFilter.MIN_LISTEN_MS, MinListenFilter.thresholdMs(null))
        assertEquals(MinListenFilter.MIN_LISTEN_MS, MinListenFilter.thresholdMs(0))
        assertEquals(MinListenFilter.MIN_LISTEN_MS, MinListenFilter.thresholdMs(-1))
        assertEquals(listOf(ev(t0)), MinListenFilter.countable(listOf(ev(t0), ev(t0 + 20_000))))
    }

    @Test fun predecessorIsPreviousChronologicalPlayAnyTrack() {
        // Q is track B; P is track A 10s later → A excluded even though different track.
        val kept = MinListenFilter.countable(listOf(ev(t0, B), ev(t0 + 10 * sec, A)))
        assertEquals(listOf(ev(t0, B)), kept)
    }

    @Test fun skippedPlayStillServesAsPredecessorForNext() {
        // Q at 0, P at 10s (skip), R at 25s: gap(R,P)=15s < 30s → R also skipped (not gap from Q).
        val kept = MinListenFilter.countable(listOf(ev(t0), ev(t0 + 10_000, B), ev(t0 + 25_000, A)))
        assertEquals(listOf(ev(t0)), kept)
        // R at 40s after P at 10s → gap 30s → counts
        val r = ev(t0 + 40_000, A)
        assertEquals(listOf(ev(t0), r), MinListenFilter.countable(listOf(ev(t0), ev(t0 + 10_000, B), r)))
    }

    @Test fun dailyAndRollingBothSeeOnlyCountablePlays() {
        // Three same-day plays for A, but the middle one is <30s after the previous global play → daily needs 3 countable.
        val a1 = ev(t0, A)
        val noise = ev(t0 + 10_000, B) // short gap after a1; excluded; still predecessor for a2
        val a2 = ev(t0 + 20_000, A) // gap from noise = 10s → excluded
        val a3 = ev(t0 + min30 + 60_000, A) // only two countable A plays (a1, a3)
        assertTrue(QualificationEvaluator.evaluate(USER, listOf(a1, noise, a2, a3)).isEmpty())

        val a4 = ev(t0 + min30 + 120_000, A) // third countable A → daily
        val q = QualificationEvaluator.evaluate(USER, listOf(a1, noise, a2, a3, a4)).getValue(A)
        assertEquals(QualifiedBy.DAILY, q.qualifiedBy)
        assertEquals(a4.playedAtMs, q.qualifiedAtMs)

        // Rolling across days (max 2/day) so daily cannot win; inject a short-gap B that must not inflate counts.
        val rolling = listOf(
            ev(vn("2026-11-02T10:00"), A),
            ev(vn("2026-11-02T11:00"), A),
            ev(vn("2026-11-03T10:00"), A),
            ev(vn("2026-11-03T10:00") + 5_000, B), // short after 3rd A → excluded; still predecessor
            ev(vn("2026-11-03T11:00"), A),
            ev(vn("2026-11-04T09:00"), A),
        )
        val rq = QualificationEvaluator.evaluate(USER, rolling).getValue(A)
        assertEquals(QualifiedBy.ROLLING, rq.qualifiedBy)
        assertEquals(vn("2026-11-04T09:00"), rq.qualifiedAtMs)
    }

    @Test fun dedupeBeforeGapAndIdempotentOverReshuffledInput() {
        val a = ev(t0)
        val b = ev(t0 + min30)
        val dup = ev(t0) // same identity
        assertEquals(listOf(a, b), MinListenFilter.countable(listOf(b, dup, a, a)))
        assertEquals(
            QualificationEvaluator.evaluate(USER, listOf(a, b, ev(t0 + 2 * min30))),
            QualificationEvaluator.evaluate(USER, listOf(ev(t0 + 2 * min30), b, a, a)),
        )
    }

    companion object {
        const val USER = "dummy-user"
        const val A = "0000000000000000000001"
        const val B = "0000000000000000000002"
    }
}
