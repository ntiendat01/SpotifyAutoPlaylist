package com.spotifyautoplaylist.sync

import com.spotifyautoplaylist.data.local.AccountEntity
import com.spotifyautoplaylist.data.local.CoverageStatus
import com.spotifyautoplaylist.data.local.PlayEventEntity
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.local.SyncStateEntity
import com.spotifyautoplaylist.data.local.TrackEntity
import com.spotifyautoplaylist.spotify.sync.Coverage
import com.spotifyautoplaylist.spotify.sync.HistoryStore
import com.spotifyautoplaylist.spotify.sync.SyncCheckpoint
import com.spotifyautoplaylist.spotify.sync.ValidPlay

/**
 * [HistoryStore] on the TASK-021 Room schema. [importPage] is `HistoryDao.importPage` (TX-01, one
 * transaction); [commit] writes the final `sync_state` row (TX-02). `userId` must come from
 * `SpotifyAuthManager.requireAccount()`.
 */
class RoomHistoryStore(private val database: ReplayDatabase, private val clock: () -> Long = System::currentTimeMillis) : HistoryStore {

    override suspend fun checkpoint(userId: String): SyncCheckpoint? = database.syncState().get(userId)?.toCheckpoint()

    override suspend fun importPage(userId: String, plays: List<ValidPlay>, progress: SyncCheckpoint, ingestedAtMs: Long): Int {
        ensureAccount(userId)
        val tracks = plays.distinctBy { it.trackId }.map {
            TrackEntity(userId, it.trackId, it.uri, it.name, it.artistNames, it.albumName, it.imageUrl)
        }
        val events = plays.map { PlayEventEntity(userId, it.trackId, it.playedAtMs, ingestedAtMs) }
        return database.history().importPage(userId, tracks, events, progress.toEntity(userId))
    }

    override suspend fun commit(userId: String, checkpoint: SyncCheckpoint) {
        ensureAccount(userId)
        database.syncState().upsert(checkpoint.toEntity(userId))
    }

    private suspend fun ensureAccount(userId: String) {
        if (database.accounts().get(userId) == null) database.accounts().upsert(AccountEntity(userId, null, clock()))
    }
}

internal fun SyncStateEntity.toCheckpoint() = SyncCheckpoint(
    highWaterPlayedAtMs, Coverage.valueOf(coverageStatus.name), lastSuccessAtMs, lastAttemptAtMs, lastErrorCode,
    lastPageCursor, lastGapStartMs, lastGapEndMs,
)

internal fun SyncCheckpoint.toEntity(userId: String) = SyncStateEntity(
    userId, lastSuccessAtMs, highWaterPlayedAtMs, lastAttemptAtMs, CoverageStatus.valueOf(coverage.name), lastErrorCode,
    lastPageCursor, gapStartMs, gapEndMs,
)
