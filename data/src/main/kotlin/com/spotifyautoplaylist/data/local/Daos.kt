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

    @Query(
        "INSERT OR IGNORE INTO playlist_outbox (spotify_user_id, playlist_id, track_id, state, attempt_count) " +
            "SELECT spotify_user_id, :playlistId, track_id, 'PENDING', 0 FROM qualification WHERE spotify_user_id = :userId",
    )
    protected abstract suspend fun insertMissingOutbox(userId: String, playlistId: String)

    /**
     * TASK-032 TX-03/TX-05 batch: inserts every absent qualification and, when the account's managed playlist
     * is known, ensures each of the account's qualifications has an outbox row for it (PENDING if new). One
     * transaction: on any failure no qualification or outbox row from the batch persists. Existing
     * qualifications and outbox rows (any state, incl. PRESENT / REMOVED_EXTERNALLY) are never changed or
     * deleted. Returns the newly qualified track IDs in input order.
     */
    @Transaction
    open suspend fun qualifyBatch(userId: String, items: List<QualificationEntity>, playlistId: String?): List<String> {
        require(items.all { it.spotifyUserId == userId }) { "cross-account row" }
        val added = items.filter { insertQualification(it) != -1L }.map { it.trackId }
        if (playlistId != null) insertMissingOutbox(userId, playlistId)
        return added
    }

    /** Outbox rows for [playlistId] in qualification order (qualified_at_ms, track_id): the order a writer adds. */
    @Query(
        "SELECT o.* FROM playlist_outbox o JOIN qualification q ON q.spotify_user_id = o.spotify_user_id " +
            "AND q.track_id = o.track_id WHERE o.spotify_user_id = :userId AND o.playlist_id = :playlistId " +
            "ORDER BY q.qualified_at_ms, q.track_id",
    )
    abstract suspend fun outboxInQualificationOrder(userId: String, playlistId: String): List<PlaylistOutboxEntity>

    @Update protected abstract suspend fun updateOutboxRow(item: PlaylistOutboxEntity): Int

    /**
     * TASK-041 (BR-007/BR-009, 03 §5): applies one COMPLETE remote membership read of [playlistId] to the account's
     * outbox in a single transaction (no network inside). [remoteAddedAtMs] maps every remote track ID to its
     * `added_at` (or null). Never deletes rows and never causes a write to Spotify. See [reconcileRow].
     */
    @Transaction
    open suspend fun reconcileOutbox(userId: String, playlistId: String, remoteAddedAtMs: Map<String, Long?>, nowMs: Long): List<PlaylistOutboxEntity> =
        outboxInQualificationOrder(userId, playlistId).map { row ->
            val next = reconcileRow(row, row.trackId in remoteAddedAtMs, remoteAddedAtMs[row.trackId], nowMs)
            if (next != row) check(updateOutboxRow(next) == 1)
            next
        }
}

/**
 * Pure TASK-041 rule for one outbox row given a complete remote read:
 * - present remotely: PENDING/CHECKING_REMOTE/RETRY_LATER/SENDING/VERIFY_REMOTE -> PRESENT (never re-POSTed);
 *   added_at_ms from remote `added_at`, else the verification time.
 * - absent: PRESENT -> REMOVED_EXTERNALLY (never re-added); CHECKING_REMOTE/RETRY_LATER -> PENDING (still missing).
 * - unchanged: PENDING absent, SENDING/VERIFY_REMOTE absent (TASK-042 decides), REMOVED_EXTERNALLY and BLOCKED
 *   whatever the remote says (no documented transition out; tracking reset is not in v1).
 */
fun reconcileRow(row: PlaylistOutboxEntity, present: Boolean, remoteAddedAtMs: Long?, nowMs: Long): PlaylistOutboxEntity =
    when (row.state) {
        OutboxState.PENDING, OutboxState.CHECKING_REMOTE, OutboxState.RETRY_LATER,
        OutboxState.SENDING, OutboxState.VERIFY_REMOTE ->
            if (present) row.copy(state = OutboxState.PRESENT, addedAtMs = remoteAddedAtMs ?: row.addedAtMs ?: nowMs, lastErrorCode = null)
            else if (row.state == OutboxState.CHECKING_REMOTE || row.state == OutboxState.RETRY_LATER) row.copy(state = OutboxState.PENDING)
            else row
        OutboxState.PRESENT -> if (present) row else row.copy(state = OutboxState.REMOVED_EXTERNALLY, lastAttemptMs = nowMs)
        OutboxState.REMOVED_EXTERNALLY, OutboxState.BLOCKED -> row
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
