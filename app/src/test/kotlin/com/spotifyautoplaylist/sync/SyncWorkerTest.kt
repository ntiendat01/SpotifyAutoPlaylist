package com.spotifyautoplaylist.sync

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.auth.AuthFailure
import com.spotifyautoplaylist.data.local.AccountEntity
import com.spotifyautoplaylist.data.local.ManagedPlaylistEntity
import com.spotifyautoplaylist.data.local.OutboxState
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.settings.SettingsRepository
import com.spotifyautoplaylist.data.sync.SyncStatusRepository
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.Companion.A
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.Companion.B
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.Companion.DAY
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.Companion.HOUR
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.Companion.NOW
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.Companion.P1
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.Companion.P2
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.Companion.id
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.Fault
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.FakeSpotify
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.ProcessDeath
import com.spotifyautoplaylist.playlist.ManagedPlaylistVerifier
import com.spotifyautoplaylist.playlist.OutboxWriter
import com.spotifyautoplaylist.playlist.PlaylistReconciler
import com.spotifyautoplaylist.rules.QualificationRecorder
import com.spotifyautoplaylist.spotify.PlayHistoryItem
import com.spotifyautoplaylist.spotify.RecentlyPlayedPage
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyEndpoint
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import com.spotifyautoplaylist.spotify.TrackDto
import com.spotifyautoplaylist.spotify.sync.HistorySync
import com.spotifyautoplaylist.spotify.sync.RecentlyPlayedSource
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Application stub so the worker can be tested without the production graph. */
class TestSyncApp : Application(), SyncOrchestratorProvider {
    override var syncOrchestrator: SyncOrchestrator? = null
}

/** TASK-051: orchestrator, single-flight, worker result mapping and WorkManager requests. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestSyncApp::class)
class SyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, ReplayDatabase::class.java).allowMainThreadQueries().build()
    private val dir: File = Files.createTempDirectory("sync").toFile()
    private val settings = SettingsRepository.create(File(dir, "settings.preferences_pb"))
    private val statusStore = SyncStatusRepository.create(File(dir, "status.preferences_pb"))
    private val spotify = FakeSpotify()
    private val logs = mutableListOf<String>()
    private var now = NOW
    private var active: AccountKey? = A
    private var accountError: AuthException? = null
    /** Remote recently-played per user (newest first), served as one page. */
    private val remotePlays = mutableMapOf<String, MutableList<Pair<String, Long>>>()
    private var historyCalls = 0
    private var historyFailure: Exception? = null
    private var historyGate: CompletableDeferred<Unit>? = null

    private val requireAccount: suspend () -> AccountKey = { active ?: throw AuthException(AuthFailure.REAUTH_REQUIRED) }

    private val pass = object : SyncPass {
        override suspend fun history(userId: String): com.spotifyautoplaylist.spotify.sync.SyncResult {
            historyCalls++
            historyGate?.await()
            historyFailure?.let { throw it }
            val source = RecentlyPlayedSource {
                RecentlyPlayedPage(remotePlays[userId].orEmpty().sortedByDescending { it.second }.map { (t, at) ->
                    PlayHistoryItem(Instant.ofEpochMilli(at).toString(), TrackDto(id = t, uri = "spotify:track:$t", type = "track", name = "Dummy"))
                }, next = null)
            }
            return HistorySync(source, RoomHistoryStore(db) { now }, { Instant.ofEpochMilli(now) }).sync(userId)
        }
        override suspend fun evaluate(account: AccountKey) = QualificationRecorder(db, settings).evaluate(account).newlyQualified.size
        override suspend fun autoSyncEnabled(account: AccountKey) = settings.current(account).autoSyncEnabled
        override fun writer(account: AccountKey): OutboxWriter {
            val verifier = ManagedPlaylistVerifier(requireAccount, { db.playlists().managed(it) }, spotify::playlist)
            return OutboxWriter(requireAccount, PlaylistReconciler(requireAccount, verifier, spotify::page, db, { now }), db, spotify::add, { now })
        }
    }

    /** A fresh orchestrator is a process restart: only Room, DataStore and the remote survive. */
    private fun orchestrator() = SyncOrchestrator({ accountError?.let { throw it }; active }, pass, statusStore, { now }, { logs += it })
    private var orch = orchestrator()
    private fun run(trigger: SyncTrigger = SyncTrigger.PERIODIC, expected: String? = null) = runBlocking { orch.run(trigger, expected) }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build())
    }

    @After fun close() { db.close(); dir.deleteRecursively() }

    private fun link(account: AccountKey = A, playlist: String = P1, verified: Boolean = true) = runBlocking {
        db.accounts().upsert(AccountEntity(account.spotifyUserId, null, 1))
        db.playlists().insertManaged(ManagedPlaylistEntity(account.spotifyUserId, playlist, null, "My Replays", null, verified))
        spotify.playlists.getOrPut(playlist) { mutableListOf() }
        spotify.owners[playlist] = account.spotifyUserId
    }

    /** [n] tracks played 3 times in one VN day on the remote (qualify at default daily 3). */
    private fun listen(n: Int, account: AccountKey = A, offset: Int = 0): List<String> {
        val ids = (offset until offset + n).map(::id)
        ids.forEachIndexed { i, t ->
            remotePlays.getOrPut(account.spotifyUserId) { mutableListOf() } += listOf(t to DAY + i, t to DAY + HOUR + i, t to DAY + 2 * HOUR + i)
        }
        return ids
    }

    private fun assertExactlyOnce(ids: List<String>, playlist: String = P1, account: AccountKey = A) {
        assertEquals(emptyList<String>(), spotify.violations)
        assertEquals(ids, spotify.playlists[playlist])
        val rows = runBlocking { db.qualifications().outboxInQualificationOrder(account.spotifyUserId, playlist) }
        assertEquals(ids.sorted(), rows.map { it.trackId }.sorted())
        assertTrue(rows.all { it.state == OutboxState.PRESENT })
    }

    // ---- full pass and idempotency ----

    @Test fun `one pass imports, qualifies and adds - repeat runs add nothing more`() {
        link()
        val ids = listen(3)
        val r = run()
        assertEquals(SyncOutcome.SUCCESS, r.outcome)
        assertEquals(9, r.imported); assertEquals(3, r.newlyQualified); assertEquals(3, r.added)
        assertExactlyOnce(ids)
        repeat(3) { orch = orchestrator(); assertEquals(SyncOutcome.SUCCESS, run(SyncTrigger.MANUAL).outcome) }
        assertEquals(1, spotify.posts.size)
        assertExactlyOnce(ids)
        val more = listen(1, offset = 3)
        assertEquals(1, run().added)
        assertExactlyOnce(ids + more)
        val s = runBlocking { statusStore.current(A) }
        assertEquals("SUCCESS", s.outcome); assertEquals(now, s.lastSuccessAtMs); assertEquals(null, s.blockedUntilMs)
    }

    @Test fun `no playlist linked yet - history and evaluation still run, nothing posted, SUCCESS`() {
        listen(2)
        val r = run()
        assertEquals(SyncOutcome.SUCCESS, r.outcome)
        assertEquals(2, r.newlyQualified)
        assertTrue(spotify.posts.isEmpty())
    }

    @Test fun `crash after POST - restart resumes the interrupted row without a re-POST`() {
        link()
        val ids = listen(2)
        spotify.faults += Fault.DEATH_AFTER_POST
        try { run(); error("expected death") } catch (_: ProcessDeath) {}
        assertEquals(listOf(OutboxState.SENDING), runBlocking { db.qualifications().outboxInQualificationOrder(A.spotifyUserId, P1) }.map { it.state }.distinct())
        orch = orchestrator()
        assertEquals(SyncOutcome.SUCCESS, run().outcome)
        assertEquals(1, spotify.posts.size)
        assertExactlyOnce(ids)
    }

    @Test fun `crash before POST - restart verifies absent, later run sends exactly once`() {
        link()
        val ids = listen(2)
        spotify.faults += Fault.DEATH_BEFORE_POST
        try { run(); error("expected death") } catch (_: ProcessDeath) {}
        orch = orchestrator()
        assertEquals(SyncOutcome.SUCCESS, run().outcome) // SENDING -> VERIFY_REMOTE -> absent -> RETRY_LATER, no POST
        assertEquals(1, spotify.posts.size)
        repeat(2) { assertEquals(SyncOutcome.SUCCESS, run().outcome) }
        assertExactlyOnce(ids)
        assertEquals(2, spotify.posts.size)
    }

    // ---- serialization ----

    @Test fun `manual during a running periodic pass coalesces into it - one history call, one POST`() = runBlocking {
        link()
        val ids = listen(2)
        historyGate = CompletableDeferred()
        val periodic = async { orch.run(SyncTrigger.PERIODIC) }
        while (historyCalls == 0) yield()
        val manual = async { orch.run(SyncTrigger.MANUAL) }
        repeat(5) { yield() }
        historyGate!!.complete(Unit)
        val p = periodic.await(); val m = manual.await()
        assertEquals(1, historyCalls)
        assertFalse(p.coalesced); assertTrue(m.coalesced)
        assertEquals(p.outcome, m.outcome)
        assertEquals(1, spotify.posts.size)
        assertExactlyOnce(ids)
        // After the pass ends, a new request runs again (not stuck).
        historyGate = null
        orch.run(SyncTrigger.MANUAL)
        assertEquals(2, historyCalls)
    }

    @Test fun `different accounts are not serialized against each other`() = runBlocking {
        historyGate = CompletableDeferred()
        val a = async { orch.run(SyncTrigger.PERIODIC) }
        while (historyCalls == 0) yield()
        active = B
        val b = async { orch.run(SyncTrigger.MANUAL) }
        while (historyCalls < 2) yield()
        historyGate!!.complete(Unit)
        a.await(); val rb = b.await()
        assertFalse(rb.coalesced)
        assertEquals(2, historyCalls)
    }

    // ---- stops without POSTs ----

    @Test fun `no account - nothing called, nothing posted`() {
        active = null
        assertEquals(SyncOutcome.NO_ACCOUNT, run().outcome)
        assertEquals(0, historyCalls); assertTrue(spotify.posts.isEmpty())
    }

    @Test fun `auth required - signed-out reason or 401 during history stops before writes`() {
        link(); listen(2)
        accountError = AuthException(AuthFailure.ACCOUNT_MISMATCH)
        assertEquals(SyncOutcome.AUTH_REQUIRED, run().outcome)
        assertEquals(0, historyCalls)
        accountError = null
        historyFailure = AuthException(AuthFailure.REAUTH_REQUIRED, 401)
        assertEquals(SyncOutcome.AUTH_REQUIRED, run().outcome)
        spotify.faults += Fault.UNAUTHORIZED
        historyFailure = null
        assertEquals(SyncOutcome.AUTH_REQUIRED, run().outcome)
        assertEquals(1, spotify.posts.size) // the single rejected POST; nothing was added
        assertTrue(spotify.playlists[P1]!!.isEmpty())
    }

    @Test fun `relink required - unverified playlist pauses writes with no POST`() {
        link(verified = false); listen(2)
        assertEquals(SyncOutcome.RELINK_REQUIRED, run().outcome)
        assertTrue(spotify.posts.isEmpty())
        assertTrue(SyncWorker.toResult(SyncOutcome.RELINK_REQUIRED, 0) is ListenableWorker.Result.Failure)
    }

    @Test fun `relink required - deleted playlist pauses writes with no POST`() {
        link(); listen(2)
        spotify.playlists.remove(P1)
        assertEquals(SyncOutcome.RELINK_REQUIRED, run().outcome)
        assertTrue(spotify.posts.isEmpty())
    }

    @Test fun `forbidden 403 on POST is FORBIDDEN and the worker does not retry`() {
        link(); listen(1)
        spotify.faults += Fault.FORBIDDEN
        val r = run()
        assertEquals(SyncOutcome.FORBIDDEN, r.outcome)
        assertTrue(SyncWorker.toResult(r.outcome, 0) is ListenableWorker.Result.Failure)
    }

    // ---- Retry-After / quota: no hot retry ----

    @Test fun `Retry-After defers every run inside the window without calling Spotify`() {
        link(); val ids = listen(2)
        spotify.faults += Fault.RATE_LIMITED // Retry-After 30 s
        val r = run()
        assertEquals(SyncOutcome.RATE_LIMITED, r.outcome); assertEquals(30L, r.retryAfterSeconds)
        assertTrue(SyncWorker.toResult(r.outcome, 0) is ListenableWorker.Result.Success)
        val calls = historyCalls; val posts = spotify.posts.size
        now += 10_000
        repeat(5) { assertEquals(SyncOutcome.DEFERRED, run(SyncTrigger.MANUAL).outcome) }
        assertEquals(calls, historyCalls); assertEquals(posts, spotify.posts.size)
        assertEquals(20L, run().retryAfterSeconds)
        now += 21_000
        assertEquals(SyncOutcome.SUCCESS, run().outcome)
        assertExactlyOnce(ids)
    }

    @Test fun `quota exceeded pauses automatic and manual calls for the quota window`() {
        link(); listen(1)
        spotify.faults += Fault.QUOTA
        assertEquals(SyncOutcome.QUOTA_EXCEEDED, run().outcome)
        val calls = historyCalls
        now += SyncOrchestrator.QUOTA_PAUSE_MS - 1
        assertEquals(SyncOutcome.DEFERRED, run().outcome)
        assertEquals(SyncOutcome.DEFERRED, run(SyncTrigger.MANUAL).outcome)
        assertEquals(calls, historyCalls)
        now += 2
        assertEquals(SyncOutcome.SUCCESS, run().outcome)
    }

    @Test fun `history 429 and network errors map to RATE_LIMITED and NETWORK_ERROR`() {
        historyFailure = SpotifyApiException(SpotifyEndpoint.RECENTLY_PLAYED, SpotifyErrorKind.RATE_LIMITED, 429, 7)
        assertEquals(SyncOutcome.RATE_LIMITED, run().outcome)
        assertEquals(now + 7_000, runBlocking { statusStore.current(A) }.blockedUntilMs)
        now += 8_000
        historyFailure = SpotifyApiException(SpotifyEndpoint.RECENTLY_PLAYED, SpotifyErrorKind.NETWORK)
        assertEquals(SyncOutcome.NETWORK_ERROR, run().outcome)
        assertEquals(null, runBlocking { statusStore.current(A) }.blockedUntilMs)
    }

    // ---- account isolation ----

    @Test fun `account isolation - status, deferral and scheduled account binding`() {
        link(A, P1); link(B, P2)
        val a = listen(1, A); val b = listen(1, B, offset = 10)
        spotify.faults += Fault.RATE_LIMITED
        assertEquals(SyncOutcome.RATE_LIMITED, run().outcome)
        assertEquals("RATE_LIMITED", runBlocking { statusStore.current(A) }.outcome)
        active = B
        assertEquals(com.spotifyautoplaylist.data.sync.SyncRunStatus(), runBlocking { statusStore.current(B) }) // A's status never read as B's
        assertEquals(SyncOutcome.SUCCESS, run().outcome) // A's window does not defer B
        assertExactlyOnce(b, P2, B)
        assertEquals(com.spotifyautoplaylist.data.sync.SyncRunStatus(), runBlocking { statusStore.current(A) }) // and B's never as A's
        // Work scheduled for A never runs as B.
        assertEquals(SyncOutcome.ACCOUNT_CHANGED, run(expected = A.accountHash).outcome)
        assertTrue(spotify.playlists[P1]!!.isEmpty())
        assertFalse(a.any { it in spotify.playlists[P2]!! })
    }

    @Test fun `periodic run is skipped when auto-sync is off - manual still runs`() {
        runBlocking { settings.update(A) { it.copy(autoSyncEnabled = false) } }
        assertEquals(SyncOutcome.DISABLED, run().outcome)
        assertEquals(0, historyCalls)
        assertEquals(SyncOutcome.SUCCESS, run(SyncTrigger.MANUAL).outcome)
    }

    @Test fun `logs carry only enum names and counts`() {
        link(); listen(1)
        run()
        assertTrue(logs.isNotEmpty())
        logs.forEach { line ->
            assertFalse(line, line.contains(A.spotifyUserId) || line.contains(A.accountHash) || line.contains(P1) || line.contains(id(0)))
        }
    }

    // ---- worker ----

    private fun worker(trigger: SyncTrigger, account: String?, attempt: Int = 0): SyncWorker {
        (context as TestSyncApp).syncOrchestrator = orch
        return TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to trigger.name, SyncWorker.KEY_ACCOUNT to account))
            .setRunAttemptCount(attempt).build()
    }

    @Test fun `worker maps success, failure and bounded retry`() = runBlocking {
        link(); val ids = listen(1)
        assertTrue(worker(SyncTrigger.MANUAL, A.accountHash).doWork() is ListenableWorker.Result.Success)
        assertExactlyOnce(ids)
        assertTrue(worker(SyncTrigger.PERIODIC, B.accountHash).doWork() is ListenableWorker.Result.Failure)
        historyFailure = SpotifyApiException(SpotifyEndpoint.RECENTLY_PLAYED, SpotifyErrorKind.SERVER, 503)
        assertTrue(worker(SyncTrigger.PERIODIC, A.accountHash, attempt = 0).doWork() is ListenableWorker.Result.Retry)
        assertTrue(worker(SyncTrigger.PERIODIC, A.accountHash, attempt = SyncWorker.MAX_ATTEMPTS - 1).doWork() is ListenableWorker.Result.Failure)
        active = null
        assertTrue(worker(SyncTrigger.MANUAL, null).doWork() is ListenableWorker.Result.Failure)
        (context as TestSyncApp).syncOrchestrator = null
        assertTrue(TestListenableWorkerBuilder<SyncWorker>(context).build().doWork() is ListenableWorker.Result.Failure)
    }

    @Test fun `result table for every outcome`() {
        val expected = mapOf(
            SyncOutcome.SUCCESS to "Success", SyncOutcome.PARTIAL to "Success", SyncOutcome.DEFERRED to "Success",
            SyncOutcome.DISABLED to "Success", SyncOutcome.RATE_LIMITED to "Success", SyncOutcome.QUOTA_EXCEEDED to "Success",
            SyncOutcome.NETWORK_ERROR to "Retry", SyncOutcome.NO_ACCOUNT to "Failure", SyncOutcome.AUTH_REQUIRED to "Failure",
            SyncOutcome.RELINK_REQUIRED to "Failure", SyncOutcome.FORBIDDEN to "Failure", SyncOutcome.ACCOUNT_CHANGED to "Failure",
        )
        assertEquals(SyncOutcome.entries.toSet(), expected.keys)
        expected.forEach { (o, r) -> assertTrue("$o", SyncWorker.toResult(o, 0).javaClass.simpleName == r) }
    }

    // ---- WorkManager requests ----

    private val wm get() = WorkManager.getInstance(context)
    private fun infos(name: String) = wm.getWorkInfosForUniqueWork(name).get()

    @Test fun `periodic request - 30 minutes, network connected, exponential backoff, unique name, UPDATE keeps one`() {
        val spec = SyncScheduler.periodicRequest(A.accountHash).workSpec
        assertEquals(TimeUnit.MINUTES.toMillis(30), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(5), spec.backoffDelayDuration)
        assertEquals(A.accountHash, spec.input.getString(SyncWorker.KEY_ACCOUNT))
        val scheduler = SyncScheduler(wm)
        scheduler.enqueuePeriodic(A.accountHash)
        val first = infos(SyncScheduler.PERIODIC_WORK).single()
        scheduler.enqueuePeriodic(A.accountHash)
        val again = infos(SyncScheduler.PERIODIC_WORK).single()
        assertEquals(first.id, again.id)
        assertEquals(WorkInfo.State.ENQUEUED, again.state)
        assertEquals(TimeUnit.MINUTES.toMillis(30), again.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test fun `manual request - unique one-time work, a second request coalesces (KEEP)`() {
        val spec = SyncScheduler.manualRequest(A.accountHash).workSpec
        assertFalse(spec.isPeriodic)
        assertFalse(spec.expedited)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        val scheduler = SyncScheduler(wm)
        scheduler.requestManualSync(A.accountHash)
        val first = infos(SyncScheduler.MANUAL_WORK).single()
        scheduler.requestManualSync(A.accountHash)
        assertEquals(first.id, infos(SyncScheduler.MANUAL_WORK).single().id)
    }

    @Test fun `sign-out cancel stops periodic and manual work`() {
        val scheduler = SyncScheduler(wm)
        scheduler.enqueuePeriodic(A.accountHash); scheduler.requestManualSync(A.accountHash)
        scheduler.cancel()
        assertEquals(WorkInfo.State.CANCELLED, infos(SyncScheduler.PERIODIC_WORK).single().state)
        assertEquals(WorkInfo.State.CANCELLED, infos(SyncScheduler.MANUAL_WORK).single().state)
    }

    @Test fun `repository - schedules only with a verified playlist, runs once when stale, cancels`() = runBlocking {
        val calls = mutableListOf<String>()
        val fake = object : SyncScheduling {
            override fun enqueuePeriodic(accountHash: String) { calls += "periodic" }
            override fun requestManualSync(accountHash: String) { calls += "manual" }
            override fun cancel() { calls += "cancel" }
        }
        var verified = false
        val repo = SyncRepository({ active }, { verified }, fake, statusStore, { now })
        repo.ensureScheduled()
        assertEquals(emptyList<String>(), calls)
        verified = true
        repo.ensureScheduled()
        assertEquals(listOf("periodic", "manual"), calls)
        statusStore.record(A, com.spotifyautoplaylist.data.sync.SyncRunStatus(lastSuccessAtMs = now - 60_000))
        calls.clear(); repo.ensureScheduled()
        assertEquals(listOf("periodic"), calls)
        active = null
        assertFalse(repo.requestManualSync())
        repo.cancel()
        assertEquals(listOf("periodic", "cancel"), calls)
    }
}
