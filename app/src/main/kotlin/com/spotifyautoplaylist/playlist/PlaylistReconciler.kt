package com.spotifyautoplaylist.playlist

import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.data.local.OutboxState
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.spotify.PlaylistItemsPage
import com.spotifyautoplaylist.spotify.playlist.PlaylistMembershipReader
import com.spotifyautoplaylist.spotify.playlist.RemoteMembership

sealed interface ReconcileResult {
    /** TASK-040 gate did not return Ready; nothing was read or persisted. */
    data class NotWritable(val target: WriteTarget) : ReconcileResult
    /** Remote read incomplete; no outbox row changed (nothing marked PRESENT or REMOVED_EXTERNALLY). */
    data class Incomplete(val membership: RemoteMembership.Incomplete) : ReconcileResult
    /** Applied a complete read. [missing] = PENDING rows definitely absent remotely (TASK-042 may POST them). */
    data class Reconciled(
        val playlistId: String,
        val snapshotId: String,
        val present: List<String>,
        val missing: List<String>,
        val removedExternally: List<String>,
        val unresolved: List<String>,
    ) : ReconcileResult
}

/**
 * TASK-041 ReconcilePlaylist step (03 §3/§5, BR-007/BR-009). Order: verify mapping (API-07) → read all items
 * (API-03, outside any transaction) → re-check the bound account → one Room transaction. Never POSTs, deletes
 * or reorders anything on Spotify and never deletes local rows.
 */
class PlaylistReconciler(
    private val account: suspend () -> AccountKey,
    private val verifier: ManagedPlaylistVerifier,
    private val items: suspend (playlistId: String, offset: Int) -> PlaylistItemsPage,
    private val database: ReplayDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pageCap: Int = PlaylistMembershipReader.DEFAULT_PAGE_CAP,
) {
    suspend fun reconcile(): ReconcileResult {
        val key = account()
        val target = verifier.verify()
        if (target !is WriteTarget.Ready) return ReconcileResult.NotWritable(target)
        val membership = PlaylistMembershipReader({ items(target.playlistId, it) }, pageCap).read()
        if (membership !is RemoteMembership.Complete) return ReconcileResult.Incomplete(membership as RemoteMembership.Incomplete)
        // Account switch during the read (AUTH-006): never apply one account's remote state to another.
        if (account() != key) return ReconcileResult.NotWritable(WriteTarget.RelinkRequired(RelinkReason.OWNER))
        val rows = database.qualifications().reconcileOutbox(key.spotifyUserId, target.playlistId, membership.addedAtMs, clock())
        fun ids(vararg s: OutboxState) = rows.filter { it.state in s }.map { it.trackId }
        return ReconcileResult.Reconciled(
            target.playlistId, target.snapshotId,
            present = ids(OutboxState.PRESENT),
            missing = ids(OutboxState.PENDING),
            removedExternally = ids(OutboxState.REMOVED_EXTERNALLY),
            unresolved = ids(OutboxState.SENDING, OutboxState.VERIFY_REMOTE, OutboxState.BLOCKED),
        )
    }
}
