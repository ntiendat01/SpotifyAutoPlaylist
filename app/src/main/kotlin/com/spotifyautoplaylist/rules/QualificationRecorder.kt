package com.spotifyautoplaylist.rules

import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.data.local.QualificationEntity
import com.spotifyautoplaylist.data.local.QualifiedBy
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.settings.AccountSettings
import com.spotifyautoplaylist.data.settings.SettingsRepository
import com.spotifyautoplaylist.spotify.rules.PlayRecord
import com.spotifyautoplaylist.spotify.rules.QualificationEvaluator
import com.spotifyautoplaylist.spotify.rules.Thresholds
import com.spotifyautoplaylist.spotify.rules.QualifiedBy as RuleQualifiedBy

/** Result of one evaluation: track IDs newly qualified in this run (qualification order). */
data class EvaluationResult(val newlyQualified: List<String>, val playlistLinked: Boolean)

/**
 * TASK-032/TASK-055: runs the pure [QualificationEvaluator] (including BR-016 min-listen) over the
 * account's stored play events and persists the result with `QualificationDao.qualifyBatch` (TX-03, one
 * Room transaction). Idempotent and monotonic: re-running never changes, duplicates or deletes
 * qualifications or outbox rows — short-gap plays may stop counting toward *new* quals but never remove
 * existing ones. No network I/O. `userId` / [AccountKey] must come from `SpotifyAuthManager.requireAccount()`.
 */
class QualificationRecorder(private val database: ReplayDatabase, private val settings: SettingsRepository) {

    suspend fun evaluate(userId: String, thresholds: Thresholds): EvaluationResult {
        val events = database.history().allEvents(userId).map { PlayRecord(it.spotifyUserId, it.trackId, it.playedAtMs) }
        val items = QualificationEvaluator.evaluate(userId, events, thresholds).values
            .sortedWith(compareBy({ it.qualifiedAtMs }, { it.trackId }))
            .map { QualificationEntity(userId, it.trackId, it.qualifiedAtMs, it.qualifiedBy.toEntity()) }
        val playlistId = database.playlists().managed(userId)?.playlistId
        val added = database.qualifications().qualifyBatch(userId, items, playlistId)
        return EvaluationResult(added, playlistId != null)
    }

    /** Evaluates with the account's stored (or default) thresholds. */
    suspend fun evaluate(account: AccountKey): EvaluationResult =
        evaluate(account.spotifyUserId, settings.current(account).thresholds())

    /**
     * TX-05 / BR-010: saves new thresholds, then re-evaluates all stored events. Only newly qualifying tracks
     * are queued; raising thresholds never removes anything. Settings live in DataStore, so this is two
     * steps; if the process dies in between, the next [evaluate] completes the work (idempotent).
     */
    suspend fun changeThresholds(account: AccountKey, daily: Int, rolling: Int): EvaluationResult {
        val updated = settings.update(account) { it.copy(dailyThreshold = daily, rollingThreshold = rolling) }
        return evaluate(account.spotifyUserId, updated.thresholds())
    }
}

internal fun AccountSettings.thresholds() = Thresholds(dailyThreshold, rollingThreshold)

private fun RuleQualifiedBy.toEntity() = when (this) {
    RuleQualifiedBy.DAILY -> QualifiedBy.DAILY
    RuleQualifiedBy.ROLLING -> QualifiedBy.ROLLING
    RuleQualifiedBy.BOTH -> QualifiedBy.BOTH
}
