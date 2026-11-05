package com.spotifyautoplaylist.spotify.rules

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QualificationEvaluatorTest {
    private fun vn(text: String) = LocalDateTime.parse(text).atZone(QualificationEvaluator.ZONE).toInstant().toEpochMilli()
    private fun ev(ms: Long, track: String = A, user: String = USER) = PlayRecord(user, track, ms)
    private fun eval(vararg e: PlayRecord, t: Thresholds = Thresholds()) = QualificationEvaluator.evaluate(USER, e.toList(), t)

    @Test fun defaultsAreThreePerDayAndFivePer72h() {
        assertEquals(3, Thresholds().daily)
        assertEquals(5, Thresholds().rolling)
    }

    @Test fun tc001_twoPlaysInOneDayDoNotQualify() {
        assertTrue(eval(ev(vn("2026-11-04T08:00")), ev(vn("2026-11-04T20:00"))).isEmpty())
    }

    @Test fun tc002_threePlaysInOneDayQualifyAtThird() {
        val q = eval(ev(vn("2026-11-04T00:00")), ev(vn("2026-11-04T12:00")), ev(vn("2026-11-04T23:59:59.999"))).getValue(A)
        assertEquals(vn("2026-11-04T23:59:59.999"), q.qualifiedAtMs)
        assertEquals(QualifiedBy.DAILY, q.qualifiedBy)
    }

    @Test fun tc003_dayBoundaryAtVietnamMidnight() {
        // 2 by 23:59:59.999 on D, third at exactly 00:00 D+1 belongs to D+1.
        assertTrue(eval(ev(vn("2026-11-04T10:00")), ev(vn("2026-11-04T23:59:59.999")), ev(vn("2026-11-05T00:00"))).isEmpty())
        assertTrue(eval(ev(vn("2026-11-04T10:00")), ev(vn("2026-11-04T23:59")), ev(vn("2026-11-05T00:01"))).isEmpty())
    }

    @Test fun vietnamDayIsNotUtcDay() {
        // 23:00/23:30 VN Nov 4 and 00:30 VN Nov 5 are the same UTC day (Nov 4) but two VN days.
        val a = vn("2026-11-04T23:00"); val b = vn("2026-11-04T23:30"); val c = vn("2026-11-05T00:30")
        assertTrue(eval(ev(a), ev(b), ev(c)).isEmpty())
        assertEquals(b, eval(ev(a), ev(b), ev(a + 1)).getValue(A).qualifiedAtMs)
    }

    @Test fun tc004_fivePlaysWithin72hAcrossDaysQualifyRolling() {
        val q = eval(
            ev(vn("2026-11-02T10:00")), ev(vn("2026-11-02T11:00")), ev(vn("2026-11-03T10:00")),
            ev(vn("2026-11-03T11:00")), ev(vn("2026-11-04T09:00")),
        ).getValue(A)
        assertEquals(vn("2026-11-04T09:00"), q.qualifiedAtMs)
        assertEquals(QualifiedBy.ROLLING, q.qualifiedBy)
    }

    @Test fun tc007_rollingWindowExcludesExactly72hAndIncludesJustInside() {
        val first = vn("2026-11-01T12:00")
        val others = listOf(vn("2026-11-02T12:00"), vn("2026-11-02T13:00"), vn("2026-11-03T12:00"))
        val atBoundary = first + QualificationEvaluator.ROLLING_WINDOW_MS
        assertTrue(eval(*(listOf(first) + others + atBoundary).map { ev(it) }.toTypedArray()).isEmpty())
        val inside = atBoundary - 1
        assertEquals(inside, eval(*(listOf(first) + others + inside).map { ev(it) }.toTypedArray()).getValue(A).qualifiedAtMs)
        // 72h + 1ms after the first is also outside.
        assertTrue(eval(*(listOf(first) + others + (atBoundary + 1)).map { ev(it) }.toTypedArray()).isEmpty())
    }

    @Test fun tc005_duplicateIdentitiesCountOnce() {
        val t = vn("2026-11-04T10:00")
        assertTrue(eval(ev(t), ev(t), ev(t), ev(t + 1)).isEmpty())
    }

    @Test fun tc006_differentTrackIdsCountSeparately() {
        val t = vn("2026-11-04T10:00")
        val r = eval(ev(t, A), ev(t + 1, A), ev(t + 2, B), ev(t + 3, B), ev(t + 4, A))
        assertEquals(setOf(A), r.keys)
    }

    @Test fun otherAccountsAreIgnored() {
        val t = vn("2026-11-04T10:00")
        assertTrue(eval(ev(t), ev(t + 1), ev(t + 2, user = OTHER)).isEmpty())
        assertTrue(QualificationEvaluator.evaluate(OTHER, listOf(ev(t), ev(t + 1), ev(t + 2))).isEmpty())
    }

    @Test fun unsortedInputGivesSameResultAndEmptyInputNothing() {
        val ts = listOf(vn("2026-11-04T22:00"), vn("2026-11-04T01:00"), vn("2026-11-04T12:00"))
        assertEquals(eval(*ts.sorted().map { ev(it) }.toTypedArray()), eval(*ts.map { ev(it) }.toTypedArray()))
        assertEquals(vn("2026-11-04T22:00"), eval(*ts.map { ev(it) }.toTypedArray()).getValue(A).qualifiedAtMs)
        assertTrue(eval().isEmpty())
    }

    @Test fun tc008_lateImportedHistoryQualifiesForOriginalDay() {
        // Evaluation uses event times, not the evaluation time.
        val q = eval(ev(vn("2026-10-01T08:00")), ev(vn("2026-10-01T09:00")), ev(vn("2026-10-01T10:00"))).getValue(A)
        assertEquals(vn("2026-10-01T10:00"), q.qualifiedAtMs)
    }

    @Test fun earliestRuleWinsAndSimultaneousIsBoth() {
        val d = vn("2026-11-04T10:00")
        // Daily (3rd at d+2) before rolling would reach 5.
        assertEquals(QualifiedBy.DAILY, eval(ev(d), ev(d + 1), ev(d + 2), ev(d + 3), ev(d + 4)).getValue(A).qualifiedBy)
        // With daily 5 and rolling 5, the fifth same-day play satisfies both at once.
        val both = eval(ev(d), ev(d + 1), ev(d + 2), ev(d + 3), ev(d + 4), t = Thresholds(daily = 5, rolling = 5)).getValue(A)
        assertEquals(QualifiedBy.BOTH, both.qualifiedBy)
        assertEquals(d + 4, both.qualifiedAtMs)
    }

    @Test fun tc009_lowerDailyThresholdQualifiesMoreAndNeverFewer() {
        val t = vn("2026-11-04T10:00")
        val events = listOf(ev(t), ev(t + 1), ev(t, B))
        val default = QualificationEvaluator.evaluate(USER, events)
        val lowered = QualificationEvaluator.evaluate(USER, events, Thresholds(daily = 2))
        assertTrue(default.isEmpty())
        assertEquals(setOf(A), lowered.keys)
        assertTrue(lowered.keys.containsAll(default.keys))
    }

    @Test fun configurableRollingThresholdAndValidation() {
        val t = vn("2026-11-01T10:00"); val day = 86_400_000L
        val r = eval(ev(t), ev(t + day), t = Thresholds(daily = 20, rolling = 2))
        assertEquals(t + day, r.getValue(A).qualifiedAtMs)
        for (bad in listOf({ Thresholds(daily = 1) }, { Thresholds(daily = 21) }, { Thresholds(rolling = 1) }, { Thresholds(rolling = 31) })) {
            assertThrows(IllegalArgumentException::class.java) { bad() }
        }
    }

    @Test fun candidatesHelpers() {
        assertNull(QualificationEvaluator.rollingCandidate(emptyList(), 5))
        assertNull(QualificationEvaluator.dailyCandidate(emptyList(), 3))
    }

    companion object {
        const val USER = "dummy-user"
        const val OTHER = "other-user"
        const val A = "0000000000000000000001"
        const val B = "0000000000000000000002"
    }
}
