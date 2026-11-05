package com.spotifyautoplaylist.sync

import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.data.sync.SyncRunStatus
import com.spotifyautoplaylist.data.sync.SyncStatusRepository
import com.spotifyautoplaylist.playlist.RelinkResult
import kotlinx.coroutines.flow.Flow

/**
 * App-facing sync API (TASK-051). No UI here: TASK-050G binds a Sync Now button to [requestManualSync] and
 * diagnostics to [status]. All calls are bound to the currently signed-in account.
 */
class SyncRepository(
    private val account: suspend () -> AccountKey?,
    private val hasVerifiedPlaylist: suspend (AccountKey) -> Boolean,
    private val scheduler: SyncScheduling,
    private val status: SyncStatusRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    /** TASK-052: serialized relink for the account (orchestrator.exclusive + PlaylistRelinker). */
    private val relink: (suspend (AccountKey, String, Boolean) -> RelinkResult)? = null,
) {
    fun status(key: AccountKey): Flow<SyncRunStatus> = status.status(key)

    /** Returns false when nobody is signed in (nothing enqueued). */
    suspend fun requestManualSync(): Boolean {
        val key = account() ?: return false
        scheduler.requestManualSync(key.accountHash)
        return true
    }

    /**
     * App start or setup verified: keep the periodic schedule for the signed-in account with a verified playlist,
     * and run once if the last successful pass is older than [FRESHNESS_MS] (03 §6).
     */
    suspend fun ensureScheduled() {
        val key = account() ?: return
        if (!hasVerifiedPlaylist(key)) return
        scheduler.enqueuePeriodic(key.accountHash)
        val last = status.current(key).lastSuccessAtMs
        if (last == null || clock() - last > FRESHNESS_MS) scheduler.requestManualSync(key.accountHash)
    }

    /**
     * TASK-052 state-only API for the future relink UI (TASK-050G/onboarding): after RELINK_REQUIRED the user
     * confirms a link to another private playlist they own. On success open outbox rows move to it and one
     * manual pass is enqueued (it reconciles before any POST). No screen is built here.
     */
    suspend fun relinkPlaylist(input: String, confirmed: Boolean): RelinkResult {
        val key = account() ?: return RelinkResult.Rejected(RelinkResult.Reason.NOT_LINKED)
        val run = relink ?: return RelinkResult.Rejected(RelinkResult.Reason.UNAVAILABLE)
        return run(key, input, confirmed).also { if (it is RelinkResult.Relinked) scheduler.requestManualSync(key.accountHash) }
    }

    /** Sign-out or account reset: stop all sync work. */
    fun cancel() = scheduler.cancel()

    companion object {
        const val FRESHNESS_MS = 10 * 60 * 1000L
    }
}
