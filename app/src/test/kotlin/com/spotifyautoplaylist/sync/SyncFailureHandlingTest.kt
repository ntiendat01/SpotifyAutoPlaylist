package com.spotifyautoplaylist.sync

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.auth.AuthFailure
import com.spotifyautoplaylist.data.local.AccountEntity
import com.spotifyautoplaylist.data.local.ManagedPlaylistEntity
import com.spotifyautoplaylist.data.local.OutboxState
import com.spotifyautoplaylist.data.local.PlaylistOutboxEntity
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
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.FakeSpotify
import com.spotifyautoplaylist.playlist.M4RecoveryIntegrationTest.Fault
import com.spotifyautoplaylist.playlist.ManagedPlaylistVerifier
import com.spotifyautoplaylist.playlist.OutboxWriter
import com.spotifyautoplaylist.playlist.PlaylistReconciler
import com.spotifyautoplaylist.playlist.PlaylistRelinker
import com.spotifyautoplaylist.playlist.RelinkResult
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TASK-052: failure classes through the real orchestrator, writer stack, Room and the relink transaction. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestSyncApp::class)
class SyncFailureHandlingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, ReplayDatabase::class.java).allowMainThreadQueries().build()
    private val dir: File = Files.createTempDirectory("sync052").toFile()
    private val settings = SettingsRepository.create(File(dir, "settings.preferences_pb"))
    private val statusStore = SyncStatusRepository.create(File(dir, "status.preferences_pb"))
    private val spotify = FakeSpotify()
    private val logs = mutableListOf<String>()
    private var now = NOW
    private var active: AccountKey? = A
    private var accountError: AuthException? = null
    private val remotePlays = mutableMapOf<String, MutableList<Pair<String, Long>>>()
    private var historyCalls = 0
    /** Thrown by the recently-played source itself, i.e. inside HistorySync (real cursor handling). */
    private var sourceFailure: Exception? = null
    private var historyGate: CompletableDeferred<Unit>? = null

    private val requireAccount: suspend () -> AccountKey = { active ?: throw AuthException(AuthFailure.REAUTH_REQUIRED) }
    private fun verifier() = ManagedPlaylistVerifier(requireAccount, { db.playlists().managed(it) }, spotify::playlist)

    private val pass = object : SyncPass {
        override suspend fun history(userId: String): com.spotifyautoplaylist.spotify.sync.SyncResult {
            historyCalls++
            historyGate?.await()
            val source = RecentlyPlayedSource {
                sourceFailure?.let { throw it }
                RecentlyPlayedPage(remotePlays[userId].orEmpty().sortedByDescending { it.second }.map { (t, at) ->
                    PlayHistoryItem(Instant.ofEpochMilli(at).toString(), TrackDto(id = t, uri = "spotify:track:$t", type = "track", name = "Dummy"))
                }, next = null)
            }
            return HistorySync(source, RoomHistoryStore(db) { now }, { Instant.ofEpochMilli(now) }).sync(userId)
        }
        override suspend fun evaluate(account: AccountKey) = QualificationRecorder(db, settings).evaluate(account).newlyQualified.size
        override suspend fun autoSyncEnabled(account: AccountKey) = settings.current(account).autoSyncEnabled
        override fun writer(account: AccountKey): OutboxWriter {
            val bound = boundAccount(account, requireAccount)
            val v = ManagedPlaylistVerifier(bound, { db.playlists().managed(it) }, spotify::playlist)
            return OutboxWriter(bound, PlaylistReconciler(bound, v, spotify::page, db, { now }), db, spotify::add, { now })
        }
    }

    private fun orchestrator() = SyncOrchestrator({ accountError?.let { throw it }; active }, pass, statusStore, { now }, { logs += it })
    private var orch = orchestrator()
    private fun run(trigger: SyncTrigger = SyncTrigger.PERIODIC) = runBlocking { orch.run(trigger) }
    private fun relink(input: String = P2, confirmed: Boolean = true, key: AccountKey = A) = runBlocking {
        orch.exclusive(key) { PlaylistRelinker(requireAccount, db, verifier(), spotify::playlist).relink(input, confirmed) }
    }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build())
    }

    @After fun close() { db.close(); dir.deleteRecursively() }

    private fun link(account: AccountKey = A, playlist: String = P1) = runBlocking {
        db.accounts().upsert(AccountEntity(account.spotifyUserId, null, 1))
        db.playlists().insertManaged(ManagedPlaylistEntity(account.spotifyUserId, playlist, null, "My Replays", null, true))
        remote(playlist, account)
    }

    private fun remote(playlist: String, owner: AccountKey = A) {
        spotify.playlists.getOrPut(playlist) { mutableListOf() }
        spotify.owners[playlist] = owner.spotifyUserId
    }

    private fun listen(n: Int, account: AccountKey = A, offset: Int = 0): List<String> {
        val ids = (offset until offset + n).map(::id)
        ids.forEachIndexed { i, t ->
            remotePlays.getOrPut(account.spotifyUserId) { mutableListOf() } += listOf(t to DAY + i, t to DAY + HOUR + i, t to DAY + 2 * HOUR + i)
        }
        return ids
    }

    private fun rows(playlist: String, account: AccountKey = A) =
        runBlocking { db.qualifications().outboxInQualificationOrder(account.spotifyUserId, playlist) }.associate { it.trackId to it.state }
    private fun status(key: AccountKey = A) = runBlocking { statusStore.current(key) }
    private fun syncState(key: AccountKey = A) = runBlocking { db.syncState().get(key.spotifyUserId) }
    private fun set(row: String, state: OutboxState, attempts: Int = 0, playlist: String = P1) = runBlocking {
        val r = checkNotNull(db.playlists().outboxItem(A.spotifyUserId, playlist, row))
        db.playlists().updateOutbox(r.copy(state = state, attemptCount = attempts, lastErrorCode = "X"))
    }
    private fun apiError(kind: SpotifyErrorKind, status: Int?, retry: Long? = null) =
        SpotifyApiException(SpotifyEndpoint.RECENTLY_PLAYED, kind, status, retry)

    // ---- (1) offline / network ----

    @Test fun `offline during history - NETWORK_ERROR, cursors and events unchanged, no POST, bounded retry, recovers exactly once`() {
        link()
        val first = listen(1)
        assertEquals(SyncOutcome.SUCCESS, run().outcome)
        val before = syncState(); val events = runBlocking { db.history().allEvents(A.spotifyUserId) }
        val more = listen(1, offset = 1)
        sourceFailure = apiError(SpotifyErrorKind.NETWORK, null)
        now += HOUR
        val r = run()
        assertEquals(SyncOutcome.NETWORK_ERROR, r.outcome)
        assertEquals(before!!.highWaterPlayedAtMs, syncState()!!.highWaterPlayedAtMs)
        assertEquals(before.lastPageCursor, syncState()!!.lastPageCursor)
        assertEquals(events, runBlocking { db.history().allEvents(A.spotifyUserId) })
        assertEquals(1, spotify.posts.size)
        val s = status()
        assertEquals("NETWORK_ERROR", s.outcome); assertEquals("NETWORK", s.errorCode); assertEquals(null, s.blockedUntilMs)
        assertEquals(NOW, s.lastSuccessAtMs)
        // Bounded, backoff-driven: retry until the attempt cap, then failure (periodic schedule keeps going).
        (0 until SyncWorker.MAX_ATTEMPTS - 1).forEach { assertEquals(ListenableWorker.Result.retry(), SyncWorker.toResult(r.outcome, it)) }
        assertTrue(SyncWorker.toResult(r.outcome, SyncWorker.MAX_ATTEMPTS - 1) is ListenableWorker.Result.Failure)
        sourceFailure = null
        assertEquals(SyncOutcome.SUCCESS, run().outcome)
        assertEquals(first + more, spotify.playlists[P1])
        assertEquals(emptyList<String>(), spotify.violations)
    }

    @Test fun `offline or 5xx during token refresh is transient, not auth-required - 429 on refresh defers`() {
        link(); listen(1)
        for ((e, expected) in listOf(
            AuthException(AuthFailure.NETWORK) to SyncOutcome.NETWORK_ERROR,
            AuthException(AuthFailure.TOKEN_HTTP, 503) to SyncOutcome.NETWORK_ERROR,
            AuthException(AuthFailure.TOKEN_RESPONSE, 200) to SyncOutcome.NETWORK_ERROR,
        )) {
            sourceFailure = e
            assertEquals(e.reason.name, expected, run().outcome)
            assertEquals(e.reason.name, status().errorCode)
        }
        sourceFailure = AuthException(AuthFailure.TOKEN_HTTP, 429, retryAfterSeconds = 120)
        assertEquals(SyncOutcome.RATE_LIMITED, run().outcome)
        assertEquals(now + 120_000, status().blockedUntilMs)
        val calls = historyCalls
        assertEquals(SyncOutcome.DEFERRED, run(SyncTrigger.MANUAL).outcome)
        assertEquals(calls, historyCalls)
        assertTrue(spotify.posts.isEmpty())
    }

    // ---- (2) 401 ----

    @Test fun `revoked refresh token - AUTH_REQUIRED, no POST, worker fails, signed-out runs call nothing`() {
        link(); listen(2)
        sourceFailure = AuthException(AuthFailure.REAUTH_REQUIRED, 400)
        val r = run()
        assertEquals(SyncOutcome.AUTH_REQUIRED, r.outcome)
        assertEquals("REAUTH_REQUIRED", status().errorCode)
        assertTrue(spotify.posts.isEmpty())
        assertTrue(SyncWorker.toResult(r.outcome, 0) is ListenableWorker.Result.Failure)
        // The manager is now SignedOut(REAUTH_REQUIRED): the next pass stops before any call.
        accountError = AuthException(AuthFailure.REAUTH_REQUIRED)
        val calls = historyCalls
        assertEquals(SyncOutcome.AUTH_REQUIRED, run().outcome)
        assertEquals(calls, historyCalls)
        assertEquals(SyncOutcome.AUTH_REQUIRED, run().also { sourceFailure = null }.outcome)
        accountError = AuthException(AuthFailure.INSUFFICIENT_SCOPE)
        assertEquals(SyncOutcome.AUTH_REQUIRED, run().outcome)
        assertTrue(spotify.posts.isEmpty())
    }

    @Test fun `401 after refresh on POST - AUTH_REQUIRED, row kept for retry, reconnect adds exactly once`() {
        link(); val ids = listen(1)
        spotify.faults += Fault.UNAUTHORIZED
        assertEquals(SyncOutcome.AUTH_REQUIRED, run().outcome)
        assertEquals(1, spotify.posts.size)
        assertEquals(OutboxState.RETRY_LATER, rows(P1)[ids[0]])
        assertEquals(SyncOutcome.SUCCESS, run().outcome)
        assertEquals(ids, spotify.playlists[P1])
        assertEquals(2, spotify.posts.size)
        assertEquals(emptyList<String>(), spotify.violations)
    }

    // ---- (3) 403 / 429 (M5 behaviour verified, not duplicated) ----

    @Test fun `403 during history is FORBIDDEN, not retried, cursors unchanged`() {
        link(); listen(1)
        sourceFailure = apiError(SpotifyErrorKind.FORBIDDEN, 403)
        val r = run()
        assertEquals(SyncOutcome.FORBIDDEN, r.outcome)
        assertEquals("FORBIDDEN", status().errorCode)
        assertEquals(null, syncState()?.highWaterPlayedAtMs)
        assertTrue(SyncWorker.toResult(r.outcome, 0) is ListenableWorker.Result.Failure)
        assertTrue(spotify.posts.isEmpty())
    }

    @Test fun `429 on POST honours Retry-After - row kept, nothing called inside the window`() {
        link(); val ids = listen(1)
        spotify.faults += Fault.RATE_LIMITED
        assertEquals(SyncOutcome.RATE_LIMITED, run().outcome)
        assertEquals(now + 30_000, status().blockedUntilMs)
        val calls = historyCalls
        now += 29_000
        assertEquals(SyncOutcome.DEFERRED, run().outcome)
        assertEquals(calls, historyCalls)
        now += 1_000
        assertEquals(SyncOutcome.SUCCESS, run().outcome)
        assertEquals(ids, spotify.playlists[P1])
    }

    // ---- (4) account mismatch ----

    @Test fun `account mismatch or reset pending at session restore - ACCOUNT_MISMATCH, nothing called, worker fails`() {
        link(); listen(1)
        for (reason in listOf(AuthFailure.ACCOUNT_MISMATCH, AuthFailure.RESET_PENDING)) {
            accountError = AuthException(reason)
            val r = run(SyncTrigger.MANUAL)
            assertEquals(SyncOutcome.ACCOUNT_MISMATCH, r.outcome)
            assertTrue(SyncWorker.toResult(r.outcome, 0) is ListenableWorker.Result.Failure)
        }
        assertEquals(0, historyCalls)
        assertTrue(spotify.posts.isEmpty())
        assertEquals(null, status().outcome) // nothing attributed to the stored account
    }

    @Test fun `account mismatch detected mid-pass stops before writes`() {
        link(); listen(1)
        sourceFailure = AuthException(AuthFailure.ACCOUNT_MISMATCH, 200)
        assertEquals(SyncOutcome.ACCOUNT_MISMATCH, run().outcome)
        assertTrue(spotify.posts.isEmpty())
        assertEquals("ACCOUNT_MISMATCH", status().errorCode)
    }

    @Test fun `account switch during the write stops before the next POST and never mixes data`() = runBlocking {
        link(); link(B, P2)
        listen(2); listen(2, B, offset = 10)
        // Switch the session to B right after A's history: A's writer sees ACCOUNT_CHANGED / foreign target.
        historyGate = null
        val switching = object : SyncPass by pass {
            override suspend fun evaluate(account: AccountKey): Int = pass.evaluate(account).also { active = B }
        }
        val r = SyncOrchestrator({ A }, switching, statusStore, { now }, { logs += it }).run(SyncTrigger.MANUAL)
        assertEquals(SyncOutcome.ACCOUNT_MISMATCH, r.outcome)
        assertTrue(spotify.posts.isEmpty())
        assertTrue(rows(P2, B).isEmpty()) // B's outbox untouched by A's pass
    }

    @Test fun `managed playlist owner changed - RELINK_REQUIRED with reason, no POST`() {
        link(); listen(1)
        spotify.owners[P1] = B.spotifyUserId
        val r = run()
        assertEquals(SyncOutcome.RELINK_REQUIRED, r.outcome)
        assertEquals("RELINK_OWNER", status().errorCode)
        assertTrue(spotify.posts.isEmpty())
    }

    // ---- (5) playlist deleted / relink ----

    @Test fun `deleted playlist then relink - open rows move once, PRESENT and REMOVED_EXTERNALLY never re-added`() {
        link()
        val ids = listen(5)
        spotify.faults += Fault.FORBIDDEN // first pass: rows stay open, nothing added
        assertEquals(SyncOutcome.FORBIDDEN, run().outcome)
        assertEquals(SyncOutcome.SUCCESS, run().outcome) // all 5 PRESENT on P1
        spotify.playlists[P1]!!.remove(ids[1])           // user removed track 1 by hand
        assertEquals(SyncOutcome.SUCCESS, run().outcome)
        assertEquals(OutboxState.REMOVED_EXTERNALLY, rows(P1)[ids[1]])
        set(ids[2], OutboxState.RETRY_LATER); set(ids[3], OutboxState.BLOCKED, attempts = 5); set(ids[4], OutboxState.PENDING)
        spotify.playlists.remove(P1)                      // deleted remotely
        val postsBefore = spotify.posts.size
        val paused = run()
        assertEquals(SyncOutcome.RELINK_REQUIRED, paused.outcome)
        assertEquals("RELINK_DELETED", status().errorCode)
        assertEquals(postsBefore, spotify.posts.size)

        remote(P2)
        val result = relink()
        assertEquals(RelinkResult.Relinked(3), result)
        assertEquals(P2, runBlocking { db.playlists().managed(A.spotifyUserId) }!!.playlistId)
        assertEquals(mapOf(ids[2] to OutboxState.PENDING, ids[3] to OutboxState.PENDING, ids[4] to OutboxState.PENDING), rows(P2))
        assertTrue(runBlocking { db.qualifications().outboxInQualificationOrder(A.spotifyUserId, P2) }.all { it.attemptCount == 0 && it.lastErrorCode == null })
        assertEquals(mapOf(ids[0] to OutboxState.PRESENT, ids[1] to OutboxState.REMOVED_EXTERNALLY), rows(P1))

        assertEquals(SyncOutcome.SUCCESS, run().outcome)
        assertEquals(listOf(ids[2], ids[3], ids[4]), spotify.playlists[P2])
        repeat(3) { orch = orchestrator(); assertEquals(SyncOutcome.SUCCESS, run(SyncTrigger.MANUAL).outcome) }
        assertEquals(listOf(ids[2], ids[3], ids[4]), spotify.playlists[P2])
        assertFalse(spotify.posts.any { it.first == P2 && (ids[0] in it.second || ids[1] in it.second) })
        assertEquals(emptyList<String>(), spotify.violations)
        assertEquals(RelinkResult.Rejected(RelinkResult.Reason.NOT_REQUIRED), relink(P1))
    }

    @Test fun `relink to a playlist that already holds a track - reconciled PRESENT, no POST for it`() {
        link(); val ids = listen(2)
        spotify.faults += Fault.FORBIDDEN
        assertEquals(SyncOutcome.FORBIDDEN, run().outcome)
        spotify.playlists.remove(P1)
        remote(P2); spotify.playlists[P2]!! += ids[0]
        assertEquals(RelinkResult.Relinked(2), relink())
        assertEquals(SyncOutcome.SUCCESS, run().outcome)
        assertEquals(ids, spotify.playlists[P2])
        assertFalse(spotify.posts.any { it.first == P2 && ids[0] in it.second })
        assertEquals(emptyList<String>(), spotify.violations)
    }

    @Test fun `relink is refused unless needed, confirmed and the candidate is an owned private playlist`() {
        link(); val ids = listen(1)
        spotify.faults += Fault.FORBIDDEN
        run()
        assertEquals(RelinkResult.Rejected(RelinkResult.Reason.NOT_REQUIRED), relink()) // P1 still healthy
        spotify.playlists.remove(P1)
        assertEquals(RelinkResult.Rejected(RelinkResult.Reason.CONFIRMATION), relink(confirmed = false))
        assertEquals(RelinkResult.Rejected(RelinkResult.Reason.INVALID_LINK), relink("not a link"))
        assertEquals(RelinkResult.Rejected(RelinkResult.Reason.NOT_FOUND), relink())
        remote(P2, B)
        assertEquals(RelinkResult.Rejected(RelinkResult.Reason.OWNER), relink())
        spotify.owners.remove(P1); spotify.playlists[P1] = mutableListOf(); spotify.owners[P1] = B.spotifyUserId // foreign now
        assertEquals(RelinkResult.Rejected(RelinkResult.Reason.SAME_PLAYLIST), relink(P1))
        assertEquals(P1, runBlocking { db.playlists().managed(A.spotifyUserId) }!!.playlistId)
        assertEquals(setOf(ids[0]), rows(P1).keys)
        assertTrue(rows(P2).isEmpty())
    }

    @Test fun `relink is refused while the current playlist cannot be verified (offline)`() {
        link(); listen(1); run()
        remote(P2)
        val original = spotify.owners.remove(P1)!!
        spotify.playlists.remove(P1)
        // Simulate offline verifier GET: a network error is Unavailable, never treated as "deleted".
        val offline = ManagedPlaylistVerifier(requireAccount, { db.playlists().managed(it) }) {
            throw SpotifyApiException(SpotifyEndpoint.PLAYLIST, SpotifyErrorKind.NETWORK, null)
        }
        val r = runBlocking { PlaylistRelinker(requireAccount, db, offline, spotify::playlist).relink(P2, true) }
        assertEquals(RelinkResult.Rejected(RelinkResult.Reason.UNAVAILABLE), r)
        assertEquals(P1, runBlocking { db.playlists().managed(A.spotifyUserId) }!!.playlistId)
        assertNotNull(original)
    }

    @Test fun `relink transaction is atomic - a failing commit leaves mapping and outbox untouched`() {
        link(); link(B, P2)
        val ids = listen(2)
        spotify.faults += Fault.FORBIDDEN
        run()
        spotify.playlists.remove(P1)
        // P2 is B's managed playlist (UNIQUE playlist_id): the update fails inside the transaction.
        spotify.owners[P2] = A.spotifyUserId
        try { relink(); fail("expected constraint failure") } catch (_: Exception) {}
        assertEquals(P1, runBlocking { db.playlists().managed(A.spotifyUserId) }!!.playlistId)
        assertEquals(ids.toSet(), rows(P1).keys)
        assertTrue(rows(P2).isEmpty())
        // Stale expectation (mapping changed under the caller) also aborts with no change.
        try {
            runBlocking { db.qualifications().relink(A.spotifyUserId, "9".repeat(22), ManagedPlaylistEntity(A.spotifyUserId, "8".repeat(22), null, "n", null, true)) }
            fail("expected abort")
        } catch (_: IllegalStateException) {}
        assertEquals(ids.toSet(), rows(P1).keys)
    }

    @Test fun `relink and evaluation are account-isolated - other account rows untouched`() {
        link(); link(B, "3".repeat(22))
        listen(2); listen(2, B, offset = 10)
        spotify.faults += Fault.FORBIDDEN
        run()
        active = B; orch = orchestrator(); assertEquals(SyncOutcome.SUCCESS, run().outcome); active = A; orch = orchestrator()
        val bRows = rows("3".repeat(22), B)
        spotify.playlists.remove(P1); remote(P2)
        assertEquals(RelinkResult.Relinked(2), relink())
        assertEquals(bRows, rows("3".repeat(22), B))
        assertEquals("3".repeat(22), runBlocking { db.playlists().managed(B.spotifyUserId) }!!.playlistId)
    }

    @Test fun `new qualification after relink targets the new playlist and the old PRESENT track is not duplicated`() {
        link(); val ids = listen(1)
        assertEquals(SyncOutcome.SUCCESS, run().outcome)
        spotify.playlists.remove(P1); remote(P2)
        assertEquals(SyncOutcome.RELINK_REQUIRED, run().outcome)
        assertEquals(RelinkResult.Relinked(0), relink())
        val more = listen(1, offset = 1)
        assertEquals(SyncOutcome.SUCCESS, run().outcome)
        assertEquals(more, spotify.playlists[P2])
        assertEquals(mapOf(ids[0] to OutboxState.PRESENT), rows(P1))
    }

    @Test fun `relink waits for a running pass of the same account`() = runBlocking {
        link(); listen(1)
        historyGate = CompletableDeferred()
        val passJob = async { orch.run(SyncTrigger.PERIODIC) }
        while (historyCalls == 0) yield()
        var entered = false
        val relinkJob = async { orch.exclusive(A) { entered = true } }
        repeat(20) { yield() }
        assertFalse(entered)
        historyGate!!.complete(Unit)
        passJob.await(); relinkJob.await()
        assertTrue(entered)
    }

    @Test fun `repository relink enqueues one manual pass on success only`() = runBlocking {
        val fake = object : SyncScheduling {
            var manual = 0
            override fun enqueuePeriodic(accountHash: String) {}
            override fun requestManualSync(accountHash: String) { manual++ }
            override fun cancel() {}
        }
        var answer: RelinkResult = RelinkResult.Rejected(RelinkResult.Reason.OWNER)
        val repo = SyncRepository({ A }, { true }, fake, statusStore, { now }, relink = { _, _, _ -> answer })
        assertEquals(answer, repo.relinkPlaylist(P2, true)); assertEquals(0, fake.manual)
        answer = RelinkResult.Relinked(1)
        assertEquals(answer, repo.relinkPlaylist(P2, true)); assertEquals(1, fake.manual)
        assertEquals(RelinkResult.Rejected(RelinkResult.Reason.NOT_LINKED),
            SyncRepository({ null }, { true }, fake, statusStore).relinkPlaylist(P2, true))
    }

    @Test fun `failure logs and persisted status stay sanitized`() {
        link(); listen(1)
        sourceFailure = AuthException(AuthFailure.NETWORK); run()
        sourceFailure = AuthException(AuthFailure.ACCOUNT_MISMATCH, 200); run()
        sourceFailure = null
        accountError = AuthException(AuthFailure.RESET_PENDING); run(); accountError = null
        spotify.owners[P1] = B.spotifyUserId; run()
        val text = logs.joinToString("\n") + status().toString() + relink().toString()
        for (secret in listOf(A.spotifyUserId, B.spotifyUserId, A.accountHash, P1, P2, id(0))) assertFalse(secret, secret in text)
        assertTrue(logs.any { "ACCOUNT_MISMATCH" in it })
    }

    @Test fun `worker result for the new ACCOUNT_MISMATCH outcome is failure`() {
        assertTrue(SyncWorker.toResult(SyncOutcome.ACCOUNT_MISMATCH, 0) is ListenableWorker.Result.Failure)
    }
}
