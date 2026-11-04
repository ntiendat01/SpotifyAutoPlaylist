package com.spotifyautoplaylist.onboarding

import androidx.lifecycle.ViewModelStore
import com.spotifyautoplaylist.auth.*
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { store.clear(); Dispatchers.resetMain() }

    private class Auth : OnboardingAuth {
        override val state = MutableStateFlow<AuthState>(AuthState.SignedOut())
        var logins = 0
        var loginFailure: AuthFailure? = null
        var waitForLogin = false
        var finished = false
        override suspend fun restore(): AuthState = state.value
        override suspend fun login(openBrowser: suspend (URI) -> Unit, verifying: () -> Unit) {
            logins++
            try {
                openBrowser(URI("https://accounts.spotify.com/authorize?state=dummy"))
                if (waitForLogin) awaitCancellation()
                verifying()
                loginFailure?.let { throw AuthException(it) }
                state.value = AuthState.SignedIn("••••wner")
            } finally { finished = true }
        }
        override suspend fun signOut() { state.value = AuthState.SignedOut() }
    }

    private class Setup : PlaylistSetup {
        var saved: SetupPlaylist? = null
        var recovering = false
        var calls = 0
        var failure: SetupFailure? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load() = SetupSnapshot(saved, recovering)
        override suspend fun create(name: String, confirmed: Boolean): SetupPlaylist {
            calls++
            assertTrue(confirmed)
            gate?.await()
            failure?.let { recovering = true; throw SetupException(it) }
            return SetupPlaylist("P".repeat(22), name).also { saved = it }
        }
        override suspend fun recover() = create("Recovered", true)
        override suspend fun link(input: String, confirmed: Boolean) = create("Linked", confirmed)
    }

    private fun model(auth: Auth? = Auth(), setup: Setup? = Setup()) = OnboardingViewModel(auth, setup).also { store.put("model", it) }

    @Test fun `missing build config shows public welcome without auth or playlist`() = runTest(dispatcher) {
        val model = model(null, null); runCurrent()
        assertFalse(model.state.value.configured)
        assertEquals(OnboardingStage.WELCOME, model.state.value.stage)
        model.connect(); runCurrent()
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.playlist)
    }

    @Test fun `verified stored session and saved playlist restore directly without login`() = runTest(dispatcher) {
        val auth = Auth().apply { state.value = AuthState.SignedIn("••••wner") }
        val setup = Setup().apply { saved = SetupPlaylist("P".repeat(22), "Existing") }
        val model = model(auth, setup); runCurrent()
        assertEquals(OnboardingStage.READY, model.state.value.stage)
        assertEquals("Existing", model.state.value.playlist?.name)
        assertEquals(0, auth.logins)
    }

    @Test fun `signed in without playlist enters setup with recovery status`() = runTest(dispatcher) {
        val auth = Auth().apply { state.value = AuthState.SignedIn("••••wner") }
        val model = model(auth, Setup().apply { recovering = true }); runCurrent()
        assertEquals(OnboardingStage.SETUP, model.state.value.stage)
        assertTrue(model.state.value.recoveryRequired)
    }

    @Test fun `duplicate connect shares one attempt and browser acknowledgment precedes verification`() = runTest(dispatcher) {
        val auth = Auth(); val model = model(auth); runCurrent()
        val request = backgroundScope.async { model.browserRequests.first() }
        model.connect(); model.connect(); runCurrent()
        assertEquals(OnboardingStage.WAITING, model.state.value.stage)
        assertEquals(1, auth.logins)
        request.await().completion.complete(true); runCurrent()
        assertEquals(OnboardingStage.SETUP, model.state.value.stage)
        assertFalse(model.state.value.busy)
    }

    @Test fun `browser launch failure returns actionable welcome without setup`() = runTest(dispatcher) {
        val auth = Auth(); val model = model(auth); runCurrent()
        val request = backgroundScope.async { model.browserRequests.first() }
        model.connect(); runCurrent()
        request.await().completion.complete(false); runCurrent()
        assertEquals(OnboardingStage.WELCOME, model.state.value.stage)
        assertTrue(model.state.value.error!!.contains("trình duyệt"))
        assertNull(model.state.value.playlist)
    }

    @Test fun `wrong account never enters setup`() = runTest(dispatcher) {
        val auth = Auth().apply { loginFailure = AuthFailure.ACCOUNT_MISMATCH }
        val model = model(auth); runCurrent()
        val request = backgroundScope.async { model.browserRequests.first() }
        model.connect(); runCurrent(); request.await().completion.complete(true); runCurrent()
        assertEquals(OnboardingStage.WELCOME, model.state.value.stage)
        assertTrue(model.state.value.error!!.contains("không khớp"))
        assertNull(model.state.value.playlist)
    }

    @Test fun `cancel invalidates pending browser request and releases login before retry`() = runTest(dispatcher) {
        val auth = Auth(); val model = model(auth); runCurrent()
        val request = backgroundScope.async { model.browserRequests.first() }
        model.connect(); runCurrent()
        val pending = request.await()
        model.cancelLogin(); runCurrent()
        assertTrue(auth.finished)
        assertFalse(pending.completion.isActive)
        assertEquals(OnboardingStage.WELCOME, model.state.value.stage)
        assertFalse(model.state.value.busy)
    }

    @Test fun `cancel after browser opened still returns to welcome`() = runTest(dispatcher) {
        val auth = Auth().apply { waitForLogin = true }; val model = model(auth); runCurrent()
        val request = backgroundScope.async { model.browserRequests.first() }
        model.connect(); runCurrent(); request.await().completion.complete(true); runCurrent()
        model.cancelLogin(); runCurrent()
        assertEquals(OnboardingStage.WELCOME, model.state.value.stage)
        assertTrue(auth.finished)
    }

    @Test fun `duplicate create ignored and shell entered only after persistence completes`() = runTest(dispatcher) {
        val auth = Auth().apply { state.value = AuthState.SignedIn("••••wner") }
        val setup = Setup().apply { gate = CompletableDeferred() }; val model = model(auth, setup); runCurrent()
        model.editName("My real playlist"); model.create(true); model.create(true); runCurrent()
        assertEquals(1, setup.calls)
        assertEquals(OnboardingStage.SETUP, model.state.value.stage)
        setup.gate!!.complete(Unit); runCurrent()
        assertEquals(OnboardingStage.READY, model.state.value.stage)
        assertEquals("My real playlist", model.state.value.playlist?.name)
    }

    @Test fun `failed creation shows recovery and does not enter shell`() = runTest(dispatcher) {
        val auth = Auth().apply { state.value = AuthState.SignedIn("••••wner") }
        val setup = Setup().apply { failure = SetupFailure.PRIVACY }; val model = model(auth, setup); runCurrent()
        model.create(true); runCurrent()
        assertEquals(OnboardingStage.SETUP, model.state.value.stage)
        assertTrue(model.state.value.recoveryRequired)
        assertNull(model.state.value.playlist)
        assertTrue(model.state.value.error!!.contains("riêng tư"))
    }

    @Test fun `disconnect removes active UI session and permits same account reconnect`() = runTest(dispatcher) {
        val auth = Auth().apply { state.value = AuthState.SignedIn("••••wner") }
        val model = model(auth); runCurrent(); model.disconnect(); runCurrent()
        assertEquals(OnboardingStage.WELCOME, model.state.value.stage)
        assertNull(model.state.value.playlist)
        assertTrue(auth.state.value is AuthState.SignedOut)
    }

    @Test fun `unknown exceptions never leak raw failure message`() {
        assertFalse(userMessage(IllegalStateException("secret-value" )).contains("secret-value"))
        assertTrue(authMessage(AuthException(AuthFailure.CALLBACK_TIMEOUT)).contains("hết thời gian"))
        assertTrue(authMessage(AuthException(AuthFailure.INSUFFICIENT_SCOPE)).contains("quyền"))
    }
}
