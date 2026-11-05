package com.spotifyautoplaylist.playlist

import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.local.SendResult
import com.spotifyautoplaylist.spotify.SnapshotResponse
import com.spotifyautoplaylist.spotify.SpotifyClient
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import com.spotifyautoplaylist.spotify.playlist.AddItemsOutcome
import com.spotifyautoplaylist.spotify.playlist.RemoteMembership
import com.spotifyautoplaylist.spotify.playlist.addItemsOutcome

/** Outcome of one writer run. Nothing here schedules work; TASK-051 maps these to WorkManager results. */
data class WriteRun(
    val status: Status,
    /** Track IDs sent, one list per POST, in send order. */
    val posted: List<List<String>> = emptyList(),
    val errorKind: SpotifyErrorKind? = null,
    val retryAfterSeconds: Long? = null,
    val target: WriteTarget? = null,
) {
    enum class Status {
        /** Every PENDING row was sent and confirmed (or there was nothing to send). */
        DONE,
        /** TASK-040 gate failed (unlinked, deleted, foreign, public, unavailable): writes paused, nothing read or sent. */
        NOT_WRITABLE,
        /** Remote read incomplete: nothing changed, nothing sent. */
        READ_INCOMPLETE,
        /** Ambiguous POST, then a complete re-read resolved it (PRESENT or RETRY_LATER). Run stops; no re-POST. */
        AMBIGUOUS_VERIFIED,
        /** Ambiguous POST and the verification read was incomplete: rows stay VERIFY_REMOTE. */
        VERIFY_PENDING,
        /** 429 / quota: honour [retryAfterSeconds]. */
        THROTTLED,
        /** 401 after the client's single refresh: re-auth required. */
        AUTH_REQUIRED,
        /** 403 (TC-025): explicit unsupported/permission status; caller must not auto-retry. */
        FORBIDDEN,
        /** 404 on POST (TC-016): playlist gone; relink required, next run's gate pauses writes. */
        RELINK_REQUIRED,
        /** Other definite rejection (400). */
        REJECTED,
        /** Bound account changed mid-run: stopped before the next POST. */
        ACCOUNT_CHANGED,
    }
}

/**
 * TASK-042 outbox writer (03 §3/§5, 04 §6, 05 TX-04, BR-007/008/009). One run:
 * 1. journal crash leftovers: SENDING -> VERIFY_REMOTE (never re-POSTed blindly);
 * 2. TASK-041 reconcile behind the TASK-040 gate, resolving in-flight rows against a complete read;
 * 3. POST remaining PENDING rows in qualification order, batches of at most 100, each claimed
 *    (PENDING -> SENDING, attempt+1) in a committed transaction before the network call;
 * 4. settle by outcome; an ambiguous POST is verified by a fresh complete read and the run stops.
 * Never removes anything remotely. Must not run concurrently with itself for one account (TASK-051 serializes).
 */
class OutboxWriter(
    private val account: suspend () -> AccountKey,
    private val reconciler: PlaylistReconciler,
    private val database: ReplayDatabase,
    private val addTracks: suspend (playlistId: String, trackIds: List<String>) -> SnapshotResponse,
    private val clock: () -> Long = System::currentTimeMillis,
    private val batchSize: Int = SpotifyClient.MAX_ADD,
) {
    init { require(batchSize in 1..SpotifyClient.MAX_ADD) }

    suspend fun run(): WriteRun {
        val key = account()
        val user = key.spotifyUserId
        val outbox = database.qualifications()
        database.playlists().managed(user)?.let { outbox.resumeInterrupted(user, it.playlistId) }

        val reconciled = when (val r = reconciler.reconcile(resolveInFlight = true)) {
            is ReconcileResult.NotWritable -> return WriteRun(WriteRun.Status.NOT_WRITABLE, target = r.target)
            is ReconcileResult.Incomplete -> return incomplete(WriteRun.Status.READ_INCOMPLETE, r.membership)
            is ReconcileResult.Reconciled -> r
        }
        val playlistId = reconciled.playlistId
        val posted = mutableListOf<List<String>>()
        for (batch in reconciled.missing.chunked(batchSize)) {
            if (account() != key) return WriteRun(WriteRun.Status.ACCOUNT_CHANGED, posted)
            val claimed = outbox.claimForSend(user, playlistId, batch, clock())
            if (claimed.isEmpty()) continue
            val outcome = addItemsOutcome { addTracks(playlistId, claimed) }
            posted += claimed
            suspend fun settle(result: SendResult, code: String?) = outbox.settleSent(user, playlistId, claimed, result, code, clock())
            when (outcome) {
                is AddItemsOutcome.Added -> settle(SendResult.ADDED, null)
                is AddItemsOutcome.Ambiguous -> {
                    settle(SendResult.AMBIGUOUS, outcome.kind.name)
                    return when (val v = reconciler.reconcile(resolveInFlight = true)) {
                        is ReconcileResult.Reconciled -> WriteRun(WriteRun.Status.AMBIGUOUS_VERIFIED, posted, outcome.kind)
                        is ReconcileResult.Incomplete -> incomplete(WriteRun.Status.VERIFY_PENDING, v.membership, posted)
                        is ReconcileResult.NotWritable -> WriteRun(WriteRun.Status.VERIFY_PENDING, posted, outcome.kind, target = v.target)
                    }
                }
                is AddItemsOutcome.Throttled -> {
                    settle(SendResult.REJECTED, outcome.kind.name)
                    return WriteRun(WriteRun.Status.THROTTLED, posted, outcome.kind, outcome.retryAfterSeconds)
                }
                is AddItemsOutcome.Rejected -> {
                    settle(SendResult.REJECTED, outcome.kind.name)
                    val status = when (outcome.kind) {
                        SpotifyErrorKind.UNAUTHORIZED -> WriteRun.Status.AUTH_REQUIRED
                        SpotifyErrorKind.FORBIDDEN -> WriteRun.Status.FORBIDDEN
                        SpotifyErrorKind.NOT_FOUND -> WriteRun.Status.RELINK_REQUIRED
                        else -> WriteRun.Status.REJECTED
                    }
                    val target = if (status == WriteRun.Status.RELINK_REQUIRED) WriteTarget.RelinkRequired(RelinkReason.DELETED) else null
                    return WriteRun(status, posted, outcome.kind, target = target)
                }
            }
        }
        return WriteRun(WriteRun.Status.DONE, posted)
    }

    private fun incomplete(status: WriteRun.Status, m: RemoteMembership.Incomplete, posted: List<List<String>> = emptyList()) =
        WriteRun(status, posted, m.error, m.retryAfterSeconds)
}
