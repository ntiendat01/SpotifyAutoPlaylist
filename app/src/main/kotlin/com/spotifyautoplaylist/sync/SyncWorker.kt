package com.spotifyautoplaylist.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.spotifyautoplaylist.widget.ReplayWidgetUpdater
import java.util.concurrent.TimeUnit

/** Supplies the process-wide orchestrator; implemented by the Application so WorkManager's default initializer stays. */
interface SyncOrchestratorProvider {
    val syncOrchestrator: SyncOrchestrator?
}

/**
 * TASK-051 worker. Result mapping (no hot retry loops):
 * - SUCCESS, PARTIAL, DEFERRED, DISABLED, RATE_LIMITED, QUOTA_EXCEEDED -> success: the Retry-After/quota window is
 *   persisted and the next run skips Spotify until it has passed; WorkManager backoff (min 10 s) is never used for 429.
 * - NETWORK_ERROR -> retry with the request's exponential backoff, at most [MAX_ATTEMPTS] runs, then failure.
 * - NO_ACCOUNT, AUTH_REQUIRED, RELINK_REQUIRED, FORBIDDEN, ACCOUNT_CHANGED, ACCOUNT_MISMATCH -> failure: needs the user.
 * A failed periodic run does not cancel the periodic schedule.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val orchestrator = (applicationContext as? SyncOrchestratorProvider)?.syncOrchestrator
        if (orchestrator == null) {
            refreshWidget()
            return Result.failure()
        }
        val trigger = inputData.getString(KEY_TRIGGER)?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() } ?: SyncTrigger.PERIODIC
        val report = orchestrator.run(trigger, inputData.getString(KEY_ACCOUNT))
        refreshWidget()
        return toResult(report.outcome, runAttemptCount)
    }

    private suspend fun refreshWidget() {
        runCatching { ReplayWidgetUpdater.refresh(applicationContext) }
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        const val KEY_ACCOUNT = "account_hash"
        const val MAX_ATTEMPTS = 5

        fun toResult(outcome: SyncOutcome, attempt: Int): Result = when (outcome) {
            SyncOutcome.SUCCESS, SyncOutcome.PARTIAL, SyncOutcome.DEFERRED, SyncOutcome.DISABLED,
            SyncOutcome.RATE_LIMITED, SyncOutcome.QUOTA_EXCEEDED -> Result.success(workDataOf(KEY_OUTCOME to outcome.name))
            SyncOutcome.NETWORK_ERROR ->
                if (attempt + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure(workDataOf(KEY_OUTCOME to outcome.name))
            SyncOutcome.NO_ACCOUNT, SyncOutcome.AUTH_REQUIRED, SyncOutcome.RELINK_REQUIRED, SyncOutcome.FORBIDDEN,
            SyncOutcome.ACCOUNT_CHANGED, SyncOutcome.ACCOUNT_MISMATCH -> Result.failure(workDataOf(KEY_OUTCOME to outcome.name))
        }
        const val KEY_OUTCOME = "outcome"
    }
}

/** Scheduling API used by the app; the Sync Now button itself is TASK-050G. */
interface SyncScheduling {
    fun enqueuePeriodic(accountHash: String)
    fun requestManualSync(accountHash: String)
    fun cancel()
}

class SyncScheduler(private val workManager: WorkManager) : SyncScheduling {
    /** UPDATE keeps the existing schedule but refreshes the bound account. */
    override fun enqueuePeriodic(accountHash: String) {
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest(accountHash))
    }

    /** KEEP: a second request while one is queued or running coalesces into it (docs/06 SyncInProgress). */
    override fun requestManualSync(accountHash: String) {
        workManager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, manualRequest(accountHash))
    }

    override fun cancel() {
        workManager.cancelUniqueWork(PERIODIC_WORK)
        workManager.cancelUniqueWork(MANUAL_WORK)
    }

    companion object {
        const val PERIODIC_WORK = "spotify-autoplaylist-periodic-sync"
        const val MANUAL_WORK = "spotify-autoplaylist-manual-sync"
        const val TAG = "spotify-autoplaylist-sync"
        const val INTERVAL_MINUTES = 30L
        const val BACKOFF_MINUTES = 5L

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun periodicRequest(accountHash: String) =
            PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to SyncTrigger.PERIODIC.name, SyncWorker.KEY_ACCOUNT to accountHash))
                .addTag(TAG).build()

        // Not expedited: on API < 31 that needs a foreground notification, which v1 does not have.
        fun manualRequest(accountHash: String) =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to SyncTrigger.MANUAL.name, SyncWorker.KEY_ACCOUNT to accountHash))
                .addTag(TAG).build()
    }
}
