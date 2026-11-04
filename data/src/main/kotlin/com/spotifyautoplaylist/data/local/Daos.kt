package com.spotifyautoplaylist.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert

// DAO methods take the Spotify user ID from SpotifyAuthManager.requireAccount(); never pass an ID from
// another source. Upsert (update-in-place) is used instead of REPLACE so parent rows never cascade-delete.

@Dao
interface AccountDao {
    @Upsert suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM account WHERE spotify_user_id = :userId")
    suspend fun get(userId: String): AccountEntity?
}

@Dao
abstract class HistoryDao {
    @Upsert protected abstract suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEvents(events: List<PlayEventEntity>): List<Long>

    @Upsert protected abstract suspend fun upsertSyncState(state: SyncStateEntity)

    /**
     * TX-01: track metadata, new play events and page progress commit together or not at all.
     * Re-imported events are ignored. Returns the number of newly inserted events.
     */
    @Transaction
    open suspend fun importPage(
        userId: String,
        tracks: List<TrackEntity>,
        events: List<PlayEventEntity>,
        progress: SyncStateEntity,
    ): Int {
        require(tracks.all { it.spotifyUserId == userId } && events.all { it.spotifyUserId == userId } &&
            progress.spotifyUserId == userId) { "cross-account row" }
        upsertTracks(tracks)
        return insertEvents(events).count { it != -1L }.also { upsertSyncState(progress) }
    }

    @Query(
        "SELECT COUNT(*) FROM play_events WHERE spotify_user_id = :userId AND track_id = :trackId " +
            "AND played_at_ms >= :startMs AND played_at_ms < :endMs",
    )
    abstract suspend fun countEvents(userId: String, trackId: String, startMs: Long, endMs: Long): Int

    @Query("SELECT played_at_ms FROM play_events WHERE spotify_user_id = :userId AND track_id = :trackId ORDER BY played_at_ms")
    abstract suspend fun eventTimes(userId: String, trackId: String): List<Long>

    @Query("SELECT * FROM play_events WHERE spotify_user_id = :userId ORDER BY played_at_ms, track_id")
    abstract suspend fun allEvents(userId: String): List<PlayEventEntity>

    @Query("SELECT * FROM tracks WHERE spotify_user_id = :userId AND track_id = :trackId")
    abstract suspend fun track(userId: String, trackId: String): TrackEntity?
}

@Dao
abstract class QualificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertQualification(qualification: QualificationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOutbox(item: PlaylistOutboxEntity): Long

    /**
     * TX-03: records a first-time qualification and, when a managed playlist is known, its PENDING outbox
     * intention atomically. An existing qualification is never changed or duplicated. Returns true if new.
     */
    @Transaction
    open suspend fun qualify(qualification: QualificationEntity, playlistId: String?): Boolean {
        if (insertQualification(qualification) == -1L) return false
        if (playlistId != null) {
            insertOutbox(PlaylistOutboxEntity(qualification.spotifyUserId, playlistId, qualification.trackId, OutboxState.PENDING))
        }
        return true
    }

    @Query("SELECT * FROM qualification WHERE spotify_user_id = :userId ORDER BY qualified_at_ms, track_id")
    abstract suspend fun all(userId: String): List<QualificationEntity>
}

@Dao
interface PlaylistDao {
    /** Fails on a second playlist or a playlist ID already linked; never silently replaced. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertManaged(playlist: ManagedPlaylistEntity)

    @Update suspend fun updateManaged(playlist: ManagedPlaylistEntity)

    @Query("SELECT * FROM managed_playlist WHERE spotify_user_id = :userId")
    suspend fun managed(userId: String): ManagedPlaylistEntity?

    @Query("SELECT * FROM playlist_outbox WHERE spotify_user_id = :userId AND playlist_id = :playlistId AND track_id = :trackId")
    suspend fun outboxItem(userId: String, playlistId: String, trackId: String): PlaylistOutboxEntity?

    @Query("SELECT * FROM playlist_outbox WHERE spotify_user_id = :userId AND state IN (:states) ORDER BY track_id")
    suspend fun outboxIn(userId: String, states: List<OutboxState>): List<PlaylistOutboxEntity>

    /** TX-04 step: persist e.g. VERIFY_REMOTE before the network POST; network I/O never runs inside a transaction. */
    @Update suspend fun updateOutbox(item: PlaylistOutboxEntity): Int
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE spotify_user_id = :userId")
    suspend fun get(userId: String): SyncStateEntity?

    @Upsert suspend fun upsert(state: SyncStateEntity)
}
