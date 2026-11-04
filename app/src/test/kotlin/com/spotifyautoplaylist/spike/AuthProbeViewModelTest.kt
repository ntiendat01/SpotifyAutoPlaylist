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
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
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

    @Test fun `double tap shares one listener and successful token exchange is not account verification`() = runTest(dispatcher) {
        var binds = 0
        var exchanges = 0
        val listener = FakeListener()
        val model = model(factory = { binds++; listener }, exchange = TokenExchange {
            exchanges++
            TokenProbeResult(200, SpotifyAuth.scopes, 3600)
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

    private fun model(
        factory: () -> CallbackListener = { FakeListener() },
        exchange: TokenExchange = TokenExchange { TokenProbeResult(200, SpotifyAuth.scopes, 3600) },
    ) = AuthProbeViewModel(factory, exchange, dispatcher).also { store.put("probe", it) }

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
