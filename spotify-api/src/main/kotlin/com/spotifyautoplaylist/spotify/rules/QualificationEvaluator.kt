package com.spotifyautoplaylist.spotify.rules

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One stored play event; identity is `(userId, trackId, playedAtMs)` (BR-004). */
data class PlayRecord(val userId: String, val trackId: String, val playedAtMs: Long)

/** Inclusive thresholds (BR-001/BR-002). Defaults: >= 3 per Vietnam day, >= 5 per rolling 72 h. */
data class Thresholds(val daily: Int = DEFAULT_DAILY, val rolling: Int = DEFAULT_ROLLING) {
    init {
        require(daily in DAILY_RANGE) { "daily must be in $DAILY_RANGE" }
        require(rolling in ROLLING_RANGE) { "rolling must be in $ROLLING_RANGE" }
    }

    companion object {
        const val DEFAULT_DAILY = 3
        const val DEFAULT_ROLLING = 5
        val DAILY_RANGE = 2..20
        val ROLLING_RANGE = 2..30
    }
}

enum class QualifiedBy { DAILY, ROLLING, BOTH }

data class Qualification(val trackId: String, val qualifiedAtMs: Long, val qualifiedBy: QualifiedBy)

/**
 * TASK-031: pure BR-001..BR-003/BR-005 evaluator over stored events (02 "Counting algorithm").
 * Daily day = `[D 00:00, D+1 00:00)` in Asia/Ho_Chi_Minh; rolling window = `(t - 72h, t]` ending at an
 * event time. Returns the earliest qualification per track; persistence/monotonicity and outbox are TASK-032.
 */
object QualificationEvaluator {
    val ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
    const val ROLLING_WINDOW_MS = 72L * 60 * 60 * 1000

    /** Events of other accounts are ignored; duplicate identities count once. Input order does not matter. */
    fun evaluate(userId: String, events: Iterable<PlayRecord>, thresholds: Thresholds = Thresholds()): Map<String, Qualification> {
        val byTrack = events.asSequence()
            .filter { it.userId == userId }
            .distinct()
            .groupBy({ it.trackId }, { it.playedAtMs })
        val result = sortedMapOf<String, Qualification>()
        for ((trackId, unsorted) in byTrack) {
            val times = unsorted.sorted()
            val daily = dailyCandidate(times, thresholds.daily)
            val rolling = rollingCandidate(times, thresholds.rolling)
            val at = listOfNotNull(daily, rolling).minOrNull() ?: continue
            val by = when {
                daily == at && rolling == at -> QualifiedBy.BOTH
                daily == at -> QualifiedBy.DAILY
                else -> QualifiedBy.ROLLING
            }
            result[trackId] = Qualification(trackId, at, by)
        }
        return result
    }

    /** First event time at which some Vietnam calendar day reaches [threshold] events; [times] sorted. */
    fun dailyCandidate(times: List<Long>, threshold: Int): Long? {
        val counts = HashMap<LocalDate, Int>()
        for (t in times) {
            val day = Instant.ofEpochMilli(t).atZone(ZONE).toLocalDate()
            val n = (counts[day] ?: 0) + 1
            counts[day] = n
            if (n >= threshold) return t
        }
        return null
    }

    /** First event time `t` with >= [threshold] events in `(t - 72h, t]`; [times] sorted. */
    fun rollingCandidate(times: List<Long>, threshold: Int): Long? {
        var left = 0
        for (right in times.indices) {
            while (times[left] <= times[right] - ROLLING_WINDOW_MS) left++
            if (right - left + 1 >= threshold) return times[right]
        }
        return null
    }
}
