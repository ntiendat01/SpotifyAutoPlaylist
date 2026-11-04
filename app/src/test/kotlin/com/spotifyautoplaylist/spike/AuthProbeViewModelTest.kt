package com.spotifyautoplaylist.spike

import androidx.lifecycle.ViewModelStore
import com.spotifyautoplaylist.auth.CallbackDecision
import com.spotifyautoplaylist.auth.CallbackListener
import com.spotifyautoplaylist.auth.PkceSession
import com.spotifyautoplaylist.auth.ProbeException
import com.spotifyautoplaylist.auth.ProbeFailure
import com.spotifyautoplaylist.auth.SpotifyAuth
import com.spotifyautoplaylist.auth.TokenExchange
import com.spotifyautoplaylist.auth.TokenExchangeInput
import com.spotifyautoplaylist.auth.TokenProbeResult
import com.spotifyautoplaylist.auth.TokenGrant
import com.spotifyautoplaylist.auth.AccountProbeResult
import com.spotifyautoplaylist.auth.AccountVerification
import com.spotifyautoplaylist.auth.SmokeSession
import com.spotifyautoplaylist.auth.SmokeSessionFactory
import com.spotifyautoplaylist.auth.SmokeView
import com.spotifyautoplaylist.auth.SmokeReport
import com.spotifyautoplaylist.auth.SmokeOutcome
import com.spotifyautoplaylist.auth.RecentSmokeResult
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthProbeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val dummyId = "0123456789abcdef0123456789abcdef"

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun `invalid configuration never binds listener`() = runTest(dispatcher) {
        var binds = 0
        val model = model(factory = { binds++; FakeListener() })
        model.editClientId("not-valid")
        model.start()
        runCurrent()
        assertEquals(0, binds)
        assertEquals(ProbeFailure.INVALID_CLIENT_ID, model.state.value.failure)
    }

    @Test fun `double tap shares one listener and success requires owner verification`() = runTest(dispatcher) {
        var binds = 0
        var exchanges = 0
        val listener = FakeListener()
        val model = model(factory = { binds++; listener }, exchange = TokenExchange {
            exchanges++
            dummyGrant()
        })
        val browser = backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId)
        model.start()
        model.start()
        runCurrent()
        assertTrue(browser.await().uri.startsWith("https://accounts.spotify.com/authorize?"))
        advanceUntilIdle()
        assertEquals(1, binds)
        assertEquals(1, exchanges)
        assertTrue(listener.closed)
        assertEquals(ProbePhase.SUCCEEDED, model.state.value.phase)
        assertEquals(200, model.state.value.result?.httpStatus)
        assertEquals(200, model.state.value.account?.httpStatus)
    }

    @Test fun `port collision does not open browser or exchange code`() = runTest(dispatcher) {
        val model = model(factory = { throw ProbeException(ProbeFailure.PORT_IN_USE) }, exchange = TokenExchange {
            throw AssertionError("Must not exchange")
        })
        model.editClientId(dummyId)
        model.start()
        advanceUntilIdle()
        assertEquals(ProbeFailure.PORT_IN_USE, model.state.value.failure)
    }

    @Test fun `cancellation while browser is not collected closes listener and does not exchange`() = runTest(dispatcher) {
        val listener = FakeListener()
        val model = model(factory = { listener }, exchange = TokenExchange { throw AssertionError("Must not exchange") })
        model.editClientId(dummyId)
        model.start()
        runCurrent()
        assertEquals(ProbePhase.WAITING, model.state.value.phase)
        model.cancel()
        advanceUntilIdle()
        assertTrue(listener.closed)
        assertEquals(ProbeFailure.CANCELLED, model.state.value.failure)
    }

    @Test fun `waiting to launch browser has bounded timeout`() = runTest(dispatcher) {
        val listener = FakeListener()
        val model = model(factory = { listener })
        model.editClientId(dummyId)
        model.start()
        runCurrent()
        advanceTimeBy(SpotifyAuth.TIMEOUT_MILLIS)
        runCurrent()
        assertTrue(listener.closed)
        assertEquals(ProbeFailure.CALLBACK_TIMEOUT, model.state.value.failure)
    }

    @Test fun `cancellation during bind cannot leak acquired socket across IO handoff`() = runTest(dispatcher) {
        val listener = FakeListener()
        lateinit var model: AuthProbeViewModel
        model = model(factory = { model.cancel(); listener }, exchange = TokenExchange {
            throw AssertionError("Must not exchange")
        })
        model.editClientId(dummyId)
        model.start()
        advanceUntilIdle()
        assertTrue(listener.closed)
        assertEquals(ProbeFailure.CANCELLED, model.state.value.failure)
    }

    @Test fun `callback rejection cannot reach token exchange`() = runTest(dispatcher) {
        val listener = FakeListener(ProbeFailure.AUTHORIZATION_DENIED)
        val model = model(factory = { listener }, exchange = TokenExchange { throw AssertionError("Must not exchange") })
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId)
        model.start()
        runCurrent()
        advanceUntilIdle()
        assertTrue(listener.closed)
        assertEquals(ProbeFailure.AUTHORIZATION_DENIED, model.state.value.failure)
    }

    @Test fun `cancellation during exchange cannot later show success`() = runTest(dispatcher) {
        var exchangeCancelled = false
        val model = model(exchange = TokenExchange {
            try { awaitCancellation() } finally { exchangeCancelled = true }
        })
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId)
        model.start()
        runCurrent()
        assertEquals(ProbePhase.EXCHANGING, model.state.value.phase)
        model.cancel()
        advanceUntilIdle()
        assertTrue(exchangeCancelled)
        assertEquals(ProbeFailure.CANCELLED, model.state.value.failure)
        assertNull(model.state.value.result)
    }

    @Test fun `clearing ViewModel releases outstanding listener`() = runTest(dispatcher) {
        val listener = FakeListener()
        val model = model(factory = { listener })
        model.editClientId(dummyId)
        model.start()
        runCurrent()
        store.clear()
        advanceUntilIdle()
        assertTrue(listener.closed)
    }

    @Test fun `missing owner id fails before browser listener or token request`() = runTest(dispatcher) {
        var binds = 0
        val model = model(factory = { binds++; FakeListener() })
        model.editClientId(dummyId)
        model.editExpectedOwnerId("")
        model.start()
        advanceUntilIdle()
        assertEquals(ProbeFailure.INVALID_OWNER_ID, model.state.value.failure)
        assertEquals(0, binds)
    }

    @Test fun `wrong owner never becomes success and discards credentials`() = runTest(dispatcher) {
        val grant = dummyGrant()
        val model = model(exchange = TokenExchange { grant }, accounts = AccountVerification { _, expected ->
            assertEquals("dummy-owner", expected)
            throw ProbeException(ProbeFailure.ACCOUNT_MISMATCH, 200)
        })
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId)
        model.start()
        runCurrent()
        assertEquals(ProbePhase.FAILED, model.state.value.phase)
        assertEquals(ProbeFailure.ACCOUNT_MISMATCH, model.state.value.failure)
        assertNull(model.state.value.result)
        assertNull(model.state.value.account)
        assertTrue(grant.isClosed)
    }

    @Test fun `matching owner is checked only after OAuth and returned id is masked`() = runTest(dispatcher) {
        val grant = dummyGrant()
        var checks = 0
        val model = model(exchange = TokenExchange { grant }, accounts = AccountVerification { received, expected ->
            assertSame(grant, received)
            assertFalse(grant.isClosed)
            assertEquals("dummy-owner", expected)
            checks++
            AccountProbeResult(200, "••••wner")
        })
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId)
        model.start()
        runCurrent()
        assertEquals(1, checks)
        assertEquals("••••wner", model.state.value.account?.maskedUserId)
        assertEquals(ProbePhase.SUCCEEDED, model.state.value.phase)
        assertTrue(grant.isClosed)
        assertFalse(model.state.value.toString().contains("dummy-owner"))
    }

    @Test fun `token rejection cannot call me`() = runTest(dispatcher) {
        val model = model(exchange = TokenExchange { throw ProbeException(ProbeFailure.TOKEN_HTTP, 400) },
            accounts = AccountVerification { _, _ -> throw AssertionError("Must not call profile") })
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId)
        model.start()
        runCurrent()
        assertEquals(ProbeFailure.TOKEN_HTTP, model.state.value.failure)
        assertNull(model.state.value.account)
    }

    @Test fun `cancel during profile closes grant and cannot later show success`() = runTest(dispatcher) {
        val grant = dummyGrant()
        var profileCancelled = false
        val model = model(exchange = TokenExchange { grant }, accounts = AccountVerification { _, _ ->
            try { awaitCancellation() } finally { profileCancelled = true }
        })
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId)
        model.start()
        runCurrent()
        assertEquals(ProbePhase.VERIFYING_ACCOUNT, model.state.value.phase)
        model.editExpectedOwnerId("other-owner")
        assertEquals("dummy-owner", model.state.value.expectedOwnerId) // frozen while active
        model.cancel()
        advanceUntilIdle()
        assertTrue(profileCancelled)
        assertTrue(grant.isClosed)
        assertEquals(ProbeFailure.CANCELLED, model.state.value.failure)
        assertNull(model.state.value.account)
    }

    @Test fun `editing owner or client invalidates previous verified result`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId)
        model.start()
        runCurrent()
        assertNotNull(model.state.value.account)
        model.editExpectedOwnerId("other-owner")
        assertEquals(ProbePhase.IDLE, model.state.value.phase)
        assertNull(model.state.value.result)
        assertNull(model.state.value.account)
        model.editClientId("f".repeat(32))
        assertEquals("other-owner", model.state.value.expectedOwnerId)
        assertNull(model.state.value.account)
    }

    @Test fun `profile failure keeps no authenticated UI result`() = runTest(dispatcher) {
        for (status in listOf(401, 403, 500)) {
            val grant = dummyGrant()
            val model = model(exchange = TokenExchange { grant }, accounts = AccountVerification { _, _ ->
                throw ProbeException(ProbeFailure.PROFILE_HTTP, status)
            })
            backgroundScope.async { model.browserRequests.first() }
            model.editClientId(dummyId)
            model.start()
            runCurrent()
            assertEquals(status, model.state.value.httpStatus)
            assertEquals(ProbePhase.FAILED, model.state.value.phase)
            assertNull(model.state.value.result)
            assertTrue(grant.isClosed)
        }
    }

    @Test fun `429 prevents another login or profile request until retry after elapsed`() = runTest(dispatcher) {
        var now = 0L
        var binds = 0
        var checks = 0
        val model = model(factory = { binds++; FakeListener() }, nanoTime = { now },
            accounts = AccountVerification { _, _ ->
                checks++
                if (checks == 1) throw ProbeException(ProbeFailure.PROFILE_HTTP, 429, 45)
                AccountProbeResult(200, "••••wner")
            })
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId)
        model.start()
        runCurrent()
        assertEquals(429, model.state.value.httpStatus)
        model.start()
        runCurrent()
        assertEquals(1, checks)
        assertEquals(1, binds)
        assertEquals(45L, model.state.value.retryAfterSeconds)
        now = 45_000_000_000
        backgroundScope.async { model.browserRequests.first() }
        model.start()
        runCurrent()
        assertEquals(2, checks)
        assertEquals(ProbePhase.SUCCEEDED, model.state.value.phase)
    }

    @Test fun `late profile from canceled attempt cannot overwrite a fresh verified result`() = runTest(dispatcher) {
        val lateResult = CompletableDeferred<AccountProbeResult>()
        val grants = mutableListOf<TokenGrant>()
        var checks = 0
        val model = model(exchange = TokenExchange { dummyGrant().also(grants::add) },
            accounts = AccountVerification { _, _ ->
                checks++
                if (checks == 1) withContext(NonCancellable) { lateResult.await() }
                else AccountProbeResult(200, "••••new")
            })
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId)
        model.start()
        runCurrent()
        assertEquals(ProbePhase.VERIFYING_ACCOUNT, model.state.value.phase)
        model.cancel()
        backgroundScope.async { model.browserRequests.first() }
        model.start()
        runCurrent()
        assertEquals("••••new", model.state.value.account?.maskedUserId)
        lateResult.complete(AccountProbeResult(200, "••••old"))
        runCurrent()
        assertEquals("••••new", model.state.value.account?.maskedUserId)
        assertTrue(grants.all(TokenGrant::isClosed))
    }

    private fun model(
        factory: () -> CallbackListener = { FakeListener() },
        exchange: TokenExchange = TokenExchange { dummyGrant() },
        accounts: AccountVerification = AccountVerification { _, id -> AccountProbeResult(200, "••••${id.takeLast(4)}") },
        nanoTime: () -> Long = System::nanoTime,
        smokeFactory: SmokeSessionFactory? = null,
    ) = AuthProbeViewModel(factory, exchange, dispatcher, accounts, nanoTime, smokeFactory).also {
        store.put("probe", it)
        it.editExpectedOwnerId("dummy-owner")
    }

    private fun dummyGrant() = TokenGrant(TokenProbeResult(200, SpotifyAuth.scopes, 3600), "dummy-access-token")

    @Test fun `verified smoke session retains token only until explicit end and freezes account inputs`() = runTest(dispatcher) {
        val grant = dummyGrant()
        val session = FakeSmokeSession(grant)
        val model = model(exchange = TokenExchange { grant }, smokeFactory = SmokeSessionFactory { received, owner, client ->
            assertSame(grant, received); assertEquals("dummy-owner", owner); assertEquals(dummyId, client)
            session
        })
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId); model.start(); runCurrent()
        assertTrue(model.state.value.sessionReady)
        assertFalse(grant.isClosed)
        model.editClientId("f".repeat(32)); model.editExpectedOwnerId("other")
        assertEquals(dummyId, model.state.value.clientId)
        assertEquals("dummy-owner", model.state.value.expectedOwnerId)
        model.endSession()
        assertTrue(grant.isClosed)
        assertFalse(model.state.value.sessionReady)
        assertNull(model.state.value.smoke)
    }

    @Test fun `smoke factory failure never exposes an active session and releases token`() = runTest(dispatcher) {
        val grant = dummyGrant()
        val model = model(exchange = TokenExchange { grant }, smokeFactory = SmokeSessionFactory { _, _, _ ->
            throw ProbeException(ProbeFailure.ACCOUNT_MISMATCH, 200)
        })
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId); model.start(); runCurrent()
        model.runSmoke(SmokeAction.CREATE, true)
        assertFalse(model.state.value.sessionReady)
        assertNull(model.state.value.account)
        assertTrue(grant.isClosed)
    }

    @Test fun `double tap is serialized and cancellation of API closes session without success`() = runTest(dispatcher) {
        val grant = dummyGrant()
        var calls = 0
        val session = object : FakeSmokeSession(grant) {
            override suspend fun recent(): RecentSmokeResult { calls++; awaitCancellation() }
        }
        val model = model(exchange = TokenExchange { grant }, smokeFactory = SmokeSessionFactory { _, _, _ -> session })
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId); model.start(); runCurrent()
        model.runSmoke(SmokeAction.RECENT); model.runSmoke(SmokeAction.RECENT); runCurrent()
        assertEquals(1, calls)
        model.cancel(); runCurrent()
        assertTrue(grant.isClosed)
        assertFalse(model.state.value.sessionReady)
        assertNull(model.state.value.report)
    }

    @Test fun `API authentication quota or journal failure ends session and clears remote proof`() = runTest(dispatcher) {
        for ((reason, status) in listOf(ProbeFailure.API_HTTP to 401, ProbeFailure.QUOTA_EXCEEDED to 429, ProbeFailure.JOURNAL to null)) {
            val grant = dummyGrant()
            val session = object : FakeSmokeSession(grant) {
                override suspend fun recent(): RecentSmokeResult { throw ProbeException(reason, status, if (status == 429) 60 else null) }
            }
            val model = model(exchange = TokenExchange { grant }, smokeFactory = SmokeSessionFactory { _, _, _ -> session })
            backgroundScope.async { model.browserRequests.first() }
            model.editClientId(dummyId); model.start(); runCurrent()
            model.runSmoke(SmokeAction.RECENT); runCurrent()
            assertEquals(reason, model.state.value.failure)
            assertFalse(model.state.value.sessionReady)
            assertNull(model.state.value.smoke)
            assertTrue(grant.isClosed)
        }
    }

    @Test fun `add confirmation and captured input reach session and report remains remote sourced`() = runTest(dispatcher) {
        val grant = dummyGrant()
        var capturedInput = ""
        var capturedConfirmed = false
        val session = object : FakeSmokeSession(grant) {
            override suspend fun addTrack(input: String, confirmed: Boolean): SmokeReport {
                capturedInput = input; capturedConfirmed = confirmed
                return SmokeReport(SmokeOutcome.ALREADY_PRESENT, 2)
            }
        }
        val model = model(exchange = TokenExchange { grant }, smokeFactory = SmokeSessionFactory { _, _, _ -> session })
        backgroundScope.async { model.browserRequests.first() }
        model.editClientId(dummyId); model.start(); runCurrent()
        model.editTrack("newer-input")
        model.runSmoke(SmokeAction.ADD, confirmed = true, input = "confirmed-input"); runCurrent()
        assertEquals("confirmed-input", capturedInput)
        assertTrue(capturedConfirmed)
        assertEquals(SmokeOutcome.ALREADY_PRESENT, model.state.value.report?.outcome)
        assertNull(model.state.value.report?.postStatus)
        store.clear()
        assertTrue(grant.isClosed)
    }

    private open class FakeSmokeSession(private val grant: TokenGrant) : SmokeSession {
        override val account = AccountProbeResult(200, "••••wner")
        override fun snapshot() = SmokeView()
        override suspend fun recent() = RecentSmokeResult(0, 0, 0)
        override suspend fun preparePlaylist(confirmed: Boolean) = SmokeReport(SmokeOutcome.PLAYLIST_READY)
        override suspend fun linkPlaylist(input: String, confirmed: Boolean) = SmokeReport(SmokeOutcome.PLAYLIST_READY)
        override suspend fun addTrack(input: String, confirmed: Boolean) = SmokeReport(SmokeOutcome.PRESENT)
        override suspend fun reconcile() = SmokeReport(SmokeOutcome.ABSENT_RETRY_READY)
        override fun close() = grant.close()
    }

    private class FakeListener(private val failure: ProbeFailure? = null) : CallbackListener {
        var closed = false
        override fun await(session: PkceSession): TokenExchangeInput {
            failure?.let { throw ProbeException(it) }
            val params = session.authorizationUri.rawQuery.split('&').associate {
                val parts = it.split('=', limit = 2)
                parts[0] to URLDecoder.decode(parts[1], "UTF-8")
            }
            return (session.consumeCallback("GET", "/callback?code=dummy&state=${params.getValue("state")}")
                as CallbackDecision.Accepted).input
        }
        override fun close() { closed = true }
    }
}
