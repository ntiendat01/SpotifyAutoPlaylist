package com.spotifyautoplaylist.sync

import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.auth.AuthFailure
import com.spotifyautoplaylist.data.sync.SyncRunStatus
import com.spotifyautoplaylist.data.sync.SyncStatusRepository
import com.spotifyautoplaylist.playlist.OutboxWriter
import com.spotifyautoplaylist.playlist.WriteRun
import com.spotifyautoplaylist.playlist.WriteTarget
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import com.spotifyautoplaylist.spotify.sync.SyncResult as HistoryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncTrigger { PERIODIC, MANUAL }

/** Outcome of one full pass. Enum names are the only thing persisted or logged. */
enum class SyncOutcome {
    /** History imported, evaluation done, playlist writes DONE or no playlist linked yet. */
    SUCCESS,
    /** History and evaluation done, but playlist writes incomplete (read incomplete, verify pending, retry-later rows). */
    PARTIAL,
    /** Nobody signed in: nothing called. */
    NO_ACCOUNT,
    /** 401 after refresh, mismatch or reset pending: user must reconnect. Nothing more is called. */
    AUTH_REQUIRED,
    /** Managed playlist deleted/foreign/public/unverified: writes paused until relink. */
    RELINK_REQUIRED,
    /** 403 / definite rejection: unsupported or blocked; never auto-retried. */
    FORBIDDEN,
    /** 429 with Retry-After: no Spotify call before [SyncReport.retryAfterSeconds]. */
    RATE_LIMITED,
    /** 429 quota: automatic calls stop for [SyncOrchestrator.QUOTA_PAUSE_MS] (or Retry-After when given). */
    QUOTA_EXCEEDED,
    /** Network or 5xx: transient. */
    NETWORK_ERROR,
    /** A previous Retry-After/quota window is still open: skipped without calling Spotify. */
    DEFERRED,
    /** Periodic run while auto-sync is off in settings: skipped. */
    DISABLED,
    /** Bound account differs from the one the work was scheduled for: stopped. */
    ACCOUNT_CHANGED,
    /** TASK-052 (AUTH-006): session/grant account differs from the bound account, or reset pending. Nothing called. */
    ACCOUNT_MISMATCH,
}

data class SyncReport(
    val outcome: SyncOutcome,
    val retryAfterSeconds: Long? = null,
    val imported: Int = 0,
    val newlyQualified: Int = 0,
    val added: Int = 0,
    val write: WriteRun.Status? = null,
    /** True when this caller joined a pass that was already running for the account. */
    val coalesced: Boolean = false,
    /** Sanitized cause: a WriteRun status, SpotifyErrorKind or AuthFailure enum name. */
    val errorCode: String? = null,
)

/**
 * TASK-052 (AUTH-006): the writer stack of a pass for [pass] account. If the live session is another account
 * the pass stops with ACCOUNT_MISMATCH instead of writing for whoever is signed in now.
 */
fun boundAccount(pass: AccountKey, current: suspend () -> AccountKey): suspend () -> AccountKey = {
    val now = current()
    if (now != pass) throw AuthException(AuthFailure.ACCOUNT_MISMATCH)
    now
}

/** Dependencies of one pass, built per account so nothing leaks across accounts. */
interface SyncPass {
    suspend fun history(userId: String): HistoryResult
    suspend fun evaluate(account: AccountKey): Int
    suspend fun autoSyncEnabled(account: AccountKey): Boolean
    fun writer(account: AccountKey): OutboxWriter
}

/**
 * TASK-051: one best-effort pass, single-flight per account (03 §2/§6):
 * account check -> HistorySync -> QualificationRecorder.evaluate -> OutboxWriter (verifier + reconciler gate).
 * A caller that arrives while a pass for the same account runs joins it instead of starting a second one.
 * Never schedules anything itself; [SyncWorker] maps the report to a WorkManager result.
 */
class SyncOrchestrator(
    private val account: suspend () -> AccountKey?,
    private val pass: SyncPass,
    private val status: SyncStatusRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val lock = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<SyncReport>>()

    /** [expectedAccountHash] binds scheduled work to the account it was enqueued for (null = current). */
    suspend fun run(trigger: SyncTrigger, expectedAccountHash: String? = null): SyncReport {
        val key = try {
            account() ?: return SyncReport(SyncOutcome.NO_ACCOUNT).also { log("sync $trigger NO_ACCOUNT") }
        } catch (e: AuthException) {
            return fromAuth(e).also { log("sync $trigger ${it.outcome} ${e.reason}") }
        }
        if (expectedAccountHash != null && expectedAccountHash != key.accountHash) {
            return SyncReport(SyncOutcome.ACCOUNT_CHANGED).also { log("sync $trigger ACCOUNT_CHANGED") }
        }
        val mine = CompletableDeferred<SyncReport>()
        val existing = lock.withLock { inFlight[key.accountHash] ?: null.also { inFlight[key.accountHash] = mine } }
        if (existing != null) {
            log("sync $trigger coalesced")
            return existing.await().copy(coalesced = true)
        }
        try {
            val report = exclusive(key) { execute(trigger, key) }
            mine.complete(report)
            log("sync $trigger ${report.outcome} write=${report.write} imported=${report.imported} qualified=${report.newlyQualified} added=${report.added}")
            return report
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            if (t !is CancellationException) log("sync $trigger crashed ${t.javaClass.simpleName}")
            throw t
        } finally {
            lock.withLock { inFlight.remove(key.accountHash) }
        }
    }

    private val accountLocks = mutableMapOf<String, Mutex>()

    /**
     * TASK-052: runs [block] while no pass for [key] runs (and blocks passes meanwhile). Used for the relink
     * transaction so outbox rows are never moved under a running writer. Different accounts never wait.
     */
    suspend fun <T> exclusive(key: AccountKey, block: suspend () -> T): T {
        val m = lock.withLock { accountLocks.getOrPut(key.accountHash) { Mutex() } }
        return m.withLock { block() }
    }

    private suspend fun execute(trigger: SyncTrigger, key: AccountKey): SyncReport {
        val previous = status.current(key)
        val now = clock()
        previous.blockedUntilMs?.let { until ->
            if (until > now) return SyncReport(SyncOutcome.DEFERRED, retryAfterSeconds = (until - now + 999) / 1000)
        }
        if (trigger == SyncTrigger.PERIODIC && !pass.autoSyncEnabled(key)) return SyncReport(SyncOutcome.DISABLED)

        var imported = 0
        var qualified = 0
        val report = try {
            imported = pass.history(key.spotifyUserId).inserted
            qualified = pass.evaluate(key)
            val write = pass.writer(key).run()
            fromWrite(write, imported, qualified)
        } catch (e: AuthException) {
            fromAuth(e).copy(imported = imported, newlyQualified = qualified)
        } catch (e: SpotifyApiException) {
            SyncReport(fromKind(e.kind), e.retryAfterSeconds, imported, qualified, errorCode = e.kind.name)
        }
        status.record(key, SyncRunStatus(
            outcome = report.outcome.name,
            finishedAtMs = clock(),
            lastSuccessAtMs = if (report.outcome == SyncOutcome.SUCCESS) clock() else previous.lastSuccessAtMs,
            blockedUntilMs = blockedUntil(report, clock()),
            errorCode = report.errorCode ?: report.write?.name,
            imported = report.imported, newlyQualified = report.newlyQualified, added = report.added,
        ))
        return report
    }

    private fun fromWrite(w: WriteRun, imported: Int, qualified: Int): SyncReport {
        val added = w.posted.sumOf { it.size }
        val outcome = when (w.status) {
            WriteRun.Status.DONE -> SyncOutcome.SUCCESS
            WriteRun.Status.NOT_WRITABLE -> when (val t = w.target) {
                WriteTarget.NotLinked, null -> SyncOutcome.SUCCESS
                is WriteTarget.RelinkRequired -> SyncOutcome.RELINK_REQUIRED
                is WriteTarget.Unavailable -> fromKind(t.kind)
                is WriteTarget.Ready -> SyncOutcome.PARTIAL
            }
            WriteRun.Status.READ_INCOMPLETE, WriteRun.Status.AMBIGUOUS_VERIFIED, WriteRun.Status.VERIFY_PENDING -> SyncOutcome.PARTIAL
            WriteRun.Status.THROTTLED ->
                if (w.errorKind == SpotifyErrorKind.QUOTA_EXCEEDED) SyncOutcome.QUOTA_EXCEEDED else SyncOutcome.RATE_LIMITED
            WriteRun.Status.AUTH_REQUIRED -> SyncOutcome.AUTH_REQUIRED
            WriteRun.Status.FORBIDDEN, WriteRun.Status.REJECTED -> SyncOutcome.FORBIDDEN
            WriteRun.Status.RELINK_REQUIRED -> SyncOutcome.RELINK_REQUIRED
            WriteRun.Status.ACCOUNT_CHANGED -> SyncOutcome.ACCOUNT_CHANGED
        }
        val code = (w.target as? WriteTarget.RelinkRequired)?.let { "RELINK_${it.reason}" }
            ?: (w.target as? WriteTarget.Unavailable)?.kind?.name ?: w.errorKind?.name
        return SyncReport(outcome, w.retryAfterSeconds, imported, qualified, added, w.status, errorCode = code)
    }

    /**
     * TASK-052: token/session failures. Only a definite refusal (revoked/invalid grant after one refresh,
     * missing scope, unreadable storage) needs re-auth; offline, 5xx and 429 during refresh are transient and
     * keep the grant (SpotifyTokenClient). Account mismatch/reset pending is its own stop state.
     */
    private fun fromAuth(e: AuthException): SyncReport {
        val outcome = when (e.reason) {
            AuthFailure.QUOTA_EXCEEDED -> SyncOutcome.QUOTA_EXCEEDED
            AuthFailure.ACCOUNT_MISMATCH, AuthFailure.RESET_PENDING -> SyncOutcome.ACCOUNT_MISMATCH
            AuthFailure.NETWORK, AuthFailure.TOKEN_RESPONSE, AuthFailure.PROFILE_RESPONSE -> SyncOutcome.NETWORK_ERROR
            AuthFailure.TOKEN_HTTP, AuthFailure.PROFILE_HTTP -> when (val code = e.httpStatus) {
                429 -> SyncOutcome.RATE_LIMITED
                null -> SyncOutcome.NETWORK_ERROR
                else -> if (code >= 500) SyncOutcome.NETWORK_ERROR else SyncOutcome.AUTH_REQUIRED
            }
            else -> SyncOutcome.AUTH_REQUIRED
        }
        return SyncReport(outcome, e.retryAfterSeconds, errorCode = e.reason.name)
    }

    private fun fromKind(kind: SpotifyErrorKind) = when (kind) {
        SpotifyErrorKind.RATE_LIMITED -> SyncOutcome.RATE_LIMITED
        SpotifyErrorKind.QUOTA_EXCEEDED -> SyncOutcome.QUOTA_EXCEEDED
        SpotifyErrorKind.UNAUTHORIZED -> SyncOutcome.AUTH_REQUIRED
        SpotifyErrorKind.FORBIDDEN, SpotifyErrorKind.BAD_REQUEST -> SyncOutcome.FORBIDDEN
        SpotifyErrorKind.NOT_FOUND -> SyncOutcome.RELINK_REQUIRED
        SpotifyErrorKind.SERVER, SpotifyErrorKind.NETWORK, SpotifyErrorKind.MALFORMED_RESPONSE,
        SpotifyErrorKind.UNEXPECTED_STATUS -> SyncOutcome.NETWORK_ERROR
    }

    private fun blockedUntil(r: SyncReport, now: Long): Long? = when (r.outcome) {
        SyncOutcome.RATE_LIMITED -> now + 1000 * (r.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_S).coerceAtLeast(1)
        SyncOutcome.QUOTA_EXCEEDED -> now + (r.retryAfterSeconds?.let { it * 1000 } ?: QUOTA_PAUSE_MS)
        else -> null
    }

    companion object {
        /** 429 without a Retry-After header. */
        const val DEFAULT_RETRY_AFTER_S = 60L
        /** No documented recovery time for quota; one periodic window is skipped at minimum. */
        const val QUOTA_PAUSE_MS = 6 * 60 * 60 * 1000L
    }
}
