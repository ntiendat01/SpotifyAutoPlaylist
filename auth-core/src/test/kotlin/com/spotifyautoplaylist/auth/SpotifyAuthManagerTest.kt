package com.spotifyautoplaylist.auth

import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SpotifyAuthManagerTest {
    private val config = AuthConfig.fromBuild(DUMMY_CLIENT_ID, "owner")!!
    private val input = TokenExchangeInput(DUMMY_CLIENT_ID, "code", "verifier")
    private var clock = 1_000_000L

    @Test fun `build config is optional but never partial`() {
        assertNull(AuthConfig.fromBuild("", ""))
        assertEquals(AuthFailure.NOT_CONFIGURED,
            assertThrows(AuthException::class.java) { AuthConfig.fromBuild(DUMMY_CLIENT_ID, "") }.reason)
        assertEquals(AuthFailure.NOT_CONFIGURED,
            assertThrows(AuthException::class.java) { AuthConfig.fromBuild("short", "owner") }.reason)
        assertFalse(config.toString().contains("owner"))
    }

    @Test fun `login verifies identity before persisting and restores after restart`() = runBlocking {
        val store = MemoryStore()
        val signedIn = manager(store).completeLogin(input)
        assertEquals(OwnerIdentity.mask("owner"), signedIn.maskedUserId)
        val saved = store.value!!
        assertEquals("acct-1", saved.accountId)
        assertEquals("refresh-0", saved.refreshToken)
        assertEquals(clock + 3_600_000, saved.expiresAtEpochMillis)
        val restarted = manager(store)
        assertTrue(restarted.restore() is AuthState.SignedIn)
        assertEquals("access-0", restarted.accessToken().value)
    }

    @Test fun `wrong account or missing account id persists nothing`() = runBlocking {
        for (failure in listOf(AuthFailure.ACCOUNT_MISMATCH, AuthFailure.PROFILE_RESPONSE)) {
            val store = MemoryStore()
            val identity = IdentityVerifier { _, _ -> throw AuthException(failure, 200) }
            val bindings = MemoryBindings()
            val manager = manager(store, bindings = bindings, identity = identity)
            assertEquals(failure, failureOf { manager.completeLogin(input) }.reason)
            assertNull(store.value)
            assertEquals(0, store.saves)
            assertNull(bindings.value)
            assertEquals(AuthState.SignedOut(), manager.state.value)
        }
    }

    @Test fun `login for a different account never replaces the active grant`() = runBlocking {
        val store = MemoryStore()
        val bindings = MemoryBindings()
        manager(store, bindings = bindings).completeLogin(input)
        val other = manager(store, bindings = bindings, identity = asAccount("acct-2"))
        other.restore()
        assertEquals(AuthFailure.ACCOUNT_MISMATCH, failureOf { other.completeLogin(input) }.reason)
        assertEquals("acct-1", store.value!!.accountId)
    }

    @Test fun `grant for another client or owner is not activated`() = runBlocking {
        for (stored in listOf(credentials(clientId = "f".repeat(32)), credentials(owner = "someone-else"))) {
            val store = MemoryStore(stored)
            val manager = manager(store)
            assertEquals(AuthState.SignedOut(AuthFailure.ACCOUNT_MISMATCH), manager.restore())
            assertEquals(AuthFailure.ACCOUNT_MISMATCH, failureOf { manager.accessToken() }.reason)
            assertNotNull(store.value)
        }
    }

    @Test fun `unreadable store is discarded and requires login`() = runBlocking {
        val store = MemoryStore(credentials()).apply { failLoad = true }
        assertEquals(AuthState.SignedOut(AuthFailure.STORAGE), manager(store).restore())
        assertNull(store.value)
    }

    @Test fun `valid token is reused and expiring token refreshes with rotation persisted`() = runBlocking {
        val tokens = FakeTokens()
        val store = MemoryStore(credentials(expiresAt = clock + 10 * 60_000))
        val manager = manager(store, tokens)
        assertEquals("stored-access", manager.accessToken().value)
        assertEquals(0, tokens.refreshes.get())
        clock += 10 * 60_000 - SpotifyAuthManager.EXPIRY_MARGIN_MILLIS
        assertEquals("access-1", manager.accessToken().value)
        assertEquals(listOf("stored-refresh"), tokens.refreshInputs)
        assertEquals("refresh-1", store.value!!.refreshToken)
        assertEquals("access-1", store.value!!.accessToken)
        tokens.rotate = false
        clock += 3_600_000
        assertEquals("access-2", manager.accessToken().value)
        assertEquals("refresh-1", store.value!!.refreshToken)
    }

    @Test fun `concurrent callers share one serialized refresh`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val tokens = FakeTokens(gate = gate)
        val manager = manager(MemoryStore(credentials(expiresAt = clock)), tokens)
        val callers = List(20) { async(Dispatchers.Default) { manager.accessToken().value } }
        delay(50)
        gate.complete(Unit)
        assertEquals(setOf("access-1"), callers.awaitAll().toSet())
        assertEquals(1, tokens.refreshes.get())
        assertEquals(1, tokens.maxConcurrent)
    }

    @Test fun `401 refreshes once, stale rejection reuses newer token, repeated 401 signs out`() = runBlocking {
        val tokens = FakeTokens()
        val store = MemoryStore(credentials())
        val manager = manager(store, tokens)
        val rejected = manager.accessToken()
        val refreshed = manager.onUnauthorized(rejected)
        assertEquals("access-1", refreshed.value)
        assertEquals("access-1", manager.onUnauthorized(rejected).value)
        assertEquals(1, tokens.refreshes.get())
        val failure = failureOf { manager.onUnauthorized(refreshed) }
        assertEquals(AuthFailure.REAUTH_REQUIRED, failure.reason)
        assertEquals(AuthState.SignedOut(AuthFailure.REAUTH_REQUIRED), manager.state.value)
        assertNull(store.value)
        assertEquals(1, tokens.refreshes.get())
    }

    @Test fun `rejected refresh token clears grant while transient failure keeps it`() = runBlocking {
        val store = MemoryStore(credentials(expiresAt = clock))
        val tokens = FakeTokens(refreshFailure = AuthException(AuthFailure.NETWORK))
        val manager = manager(store, tokens)
        assertEquals(AuthFailure.NETWORK, failureOf { manager.accessToken() }.reason)
        assertTrue(manager.state.value is AuthState.SignedIn)
        assertNotNull(store.value)
        tokens.refreshFailure = AuthException(AuthFailure.REAUTH_REQUIRED, 400)
        assertEquals(AuthFailure.REAUTH_REQUIRED, failureOf { manager.accessToken() }.reason)
        assertEquals(AuthState.SignedOut(AuthFailure.REAUTH_REQUIRED), manager.state.value)
        assertNull(store.value)
    }

    @Test fun `cancelling a caller mid-refresh still persists the rotated refresh token`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val tokens = FakeTokens(gate = gate)
        val store = MemoryStore(credentials(expiresAt = clock))
        val manager = manager(store, tokens)
        val caller = launch(Dispatchers.Default) { manager.accessToken() }
        withTimeout(2_000) { while (tokens.refreshes.get() == 0) yield() }
        val cancel = launch { caller.cancelAndJoin() }
        delay(50)
        gate.complete(Unit)
        cancel.join()
        assertEquals("refresh-1", store.value!!.refreshToken)
        assertEquals("access-1", manager.accessToken().value)
    }

    @Test fun `sign out clears grant and reports storage failure honestly`() = runBlocking {
        val store = MemoryStore(credentials())
        val manager = manager(store)
        manager.restore()
        manager.signOut()
        assertNull(store.value)
        assertEquals(AuthState.SignedOut(), manager.state.value)
        assertEquals(AuthFailure.REAUTH_REQUIRED, failureOf { manager.accessToken() }.reason)
        val broken = MemoryStore(credentials()).apply { failClear = true }
        val second = manager(broken)
        assertEquals(AuthFailure.STORAGE, failureOf { second.signOut() }.reason)
        assertEquals(AuthState.SignedOut(AuthFailure.STORAGE), second.state.value)
    }

    @Test fun `TC-032 first login binds hashed account and sign-out keeps other accounts out`() = runBlocking {
        val store = MemoryStore()
        val bindings = MemoryBindings()
        val first = manager(store, bindings = bindings)
        first.completeLogin(input)
        assertEquals(accountHash("acct-1"), bindings.value!!.accountHash)
        assertFalse(bindings.value!!.accountHash!!.contains("acct-1"))
        first.signOut()
        assertNotNull(bindings.value)
        val other = manager(store, bindings = bindings, identity = asAccount("acct-2"))
        assertEquals(AuthFailure.ACCOUNT_MISMATCH, failureOf { other.completeLogin(input) }.reason)
        assertNull(store.value)
        assertEquals(accountHash("acct-1"), bindings.value!!.accountHash)
        assertEquals(OwnerIdentity.mask("owner"), manager(store, bindings = bindings).completeLogin(input).maskedUserId)
    }

    @Test fun `restored grant for an account other than the binding is never activated`() = runBlocking {
        val manager = manager(MemoryStore(credentials()), bindings = MemoryBindings(AccountBinding(accountHash("acct-9"), false)))
        assertEquals(AuthState.SignedOut(AuthFailure.ACCOUNT_MISMATCH), manager.restore())
        assertEquals(AuthFailure.ACCOUNT_MISMATCH, failureOf { manager.requireAccount() }.reason)
        assertEquals(AuthFailure.ACCOUNT_MISMATCH, failureOf { manager.accessToken() }.reason)
    }

    @Test fun `existing grant without binding is bound on restore`() = runBlocking {
        val bindings = MemoryBindings()
        val manager = manager(MemoryStore(credentials()), bindings = bindings)
        assertTrue(manager.restore() is AuthState.SignedIn)
        assertEquals(accountHash("acct-1"), bindings.value!!.accountHash)
        assertEquals(accountHash("acct-1"), manager.requireAccount().accountHash)
    }

    @Test fun `TC-033 explicit reset clears grant, runs steps in order and admits a new account`() = runBlocking {
        val store = MemoryStore()
        val bindings = MemoryBindings()
        val events = mutableListOf<String>()
        val steps = listOf(
            AccountResetStep {
                // Steps run only after the reset is durable and the grant is gone.
                assertTrue(bindings.value!!.resetPending)
                assertNull(store.value)
                events += "stop-workers"
            },
            AccountResetStep { events += "delete-account-data" },
        )
        val first = manager(store, bindings = bindings, resetSteps = steps)
        first.completeLogin(input)
        val oldKey = first.requireAccount()
        assertEquals(AuthFailure.CONFIRMATION_REQUIRED, failureOf { first.resetAccount(confirmed = false) }.reason)
        assertTrue(first.state.value is AuthState.SignedIn)
        assertTrue(events.isEmpty())
        first.resetAccount(confirmed = true)
        assertEquals(listOf("stop-workers", "delete-account-data"), events)
        assertEquals(AuthState.SignedOut(), first.state.value)
        assertNull(store.value)
        assertNull(bindings.value)
        assertEquals(AuthFailure.REAUTH_REQUIRED, failureOf { first.requireAccount() }.reason)
        val second = manager(store, bindings = bindings, identity = asAccount("acct-2"))
        second.completeLogin(input)
        val newKey = second.requireAccount()
        assertNotEquals(oldKey, newKey)
        assertEquals(accountHash("acct-2"), bindings.value!!.accountHash)
        assertFalse(newKey.toString().contains("owner"))
    }

    @Test fun `interrupted reset stays blocked across restart until retried`() = runBlocking {
        val store = MemoryStore()
        val bindings = MemoryBindings()
        var fail = true
        val steps = listOf(AccountResetStep { if (fail) throw IllegalStateException("db busy") })
        val first = manager(store, bindings = bindings, resetSteps = steps)
        first.completeLogin(input)
        assertEquals(AuthFailure.UNEXPECTED, failureOf { first.resetAccount(confirmed = true) }.reason)
        assertEquals(AuthState.SignedOut(AuthFailure.RESET_PENDING), first.state.value)
        assertTrue(bindings.value!!.resetPending)
        assertNull(store.value)

        val restarted = manager(store, bindings = bindings, identity = asAccount("acct-2"), resetSteps = steps)
        assertEquals(AuthState.SignedOut(AuthFailure.RESET_PENDING), restarted.restore())
        assertEquals(AuthFailure.RESET_PENDING, failureOf { restarted.completeLogin(input) }.reason)
        assertEquals(AuthFailure.RESET_PENDING, failureOf { restarted.requireAccount() }.reason)
        fail = false
        restarted.resetAccount(confirmed = true)
        assertNull(bindings.value)
        assertEquals(OwnerIdentity.mask("owner"), restarted.completeLogin(input).maskedUserId)
    }

    @Test fun `unreadable binding blocks login without being cleared and reset recovers`() = runBlocking {
        val store = MemoryStore(credentials())
        val bindings = MemoryBindings(AccountBinding(accountHash("acct-1"), false)).apply { failLoad = true }
        val manager = manager(store, bindings = bindings, identity = asAccount("acct-2"))
        assertEquals(AuthState.SignedOut(AuthFailure.STORAGE), manager.restore())
        assertEquals(AuthFailure.STORAGE, failureOf { manager.completeLogin(input) }.reason)
        assertNotNull(bindings.value)
        manager.resetAccount(confirmed = true)
        assertNull(bindings.value)
        assertEquals(OwnerIdentity.mask("owner"), manager.completeLogin(input).maskedUserId)
        assertEquals(accountHash("acct-2"), bindings.value!!.accountHash)
    }

    @Test fun `binding file stores only a hash, keeps pending resets and fails closed`() {
        val directory = java.nio.file.Files.createTempDirectory("binding").toFile()
        try {
            val file = java.io.File(directory, "auth/account-binding.json")
            val store = FileAccountBindingStore(file)
            assertNull(store.load())
            store.save(AccountBinding(accountHash("acct-raw-id"), false))
            assertFalse(file.readText().contains("acct-raw-id"))
            assertEquals(accountHash("acct-raw-id"), FileAccountBindingStore(file).load()!!.accountHash)
            store.save(AccountBinding(null, resetPending = true))
            val pending = FileAccountBindingStore(file).load()!!
            assertTrue(pending.resetPending)
            assertNull(pending.accountHash)
            file.writeText("""{"version":1,"accountHash":"not-a-hash","resetPending":false}""")
            assertEquals(AuthFailure.STORAGE, assertThrows(AuthException::class.java) { store.load() }.reason)
            assertThrows(IllegalArgumentException::class.java) { AccountBinding(null, resetPending = false) }
            store.clear()
            assertNull(store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `authorization flow returns callback input and always closes listener`() = runBlocking {
        val listener = BlockingListener(result = input)
        val opened = mutableListOf<URI>()
        val result = flow(listener).authorize { opened += it; listener.release.countDown() }
        assertSame(input, result)
        assertEquals("accounts.spotify.com", opened.single().host)
        assertTrue(listener.closed)

        val failing = BlockingListener(result = input)
        val browserFailure = runCatching { flow(failing).authorize { throw IllegalStateException("no browser") } }
        assertTrue(browserFailure.exceptionOrNull() is IllegalStateException)
        assertTrue(failing.closed)
    }

    @Test fun `cancelling authorization unblocks the listener promptly`() = runBlocking {
        val listener = BlockingListener(result = input)
        val job = launch(Dispatchers.Default) { flow(listener).authorize { } }
        withTimeout(2_000) { while (!listener.waiting) yield() }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(listener.closed)
        assertTrue(job.isCancelled)
    }

    @Test fun `config provider change with reset clears grant so new client can be used`() = runBlocking {
        val configRef = AtomicReference(config)
        val store = MemoryStore()
        val bindings = MemoryBindings()
        var resets = 0
        val manager = SpotifyAuthManager(
            { configRef.get() }, store, bindings, FakeTokens(), asAccount("acct-1"),
            listOf(AccountResetStep { resets++ }), { clock }, Dispatchers.Unconfined,
        )
        manager.completeLogin(input)
        assertTrue(manager.state.value is AuthState.SignedIn)

        val other = AuthConfig.fromBuild("f".repeat(32), "owner")!!
        // Mimic AppContainer.configureAuthIds: changing IDs without confirmation is rejected.
        assertEquals(AuthFailure.CONFIRMATION_REQUIRED,
            failureOf {
                val previous = configRef.get()
                val changing = previous.clientId != other.clientId || previous.expectedOwnerId != other.expectedOwnerId
                if (changing) throw AuthException(AuthFailure.CONFIRMATION_REQUIRED)
            }.reason)
        assertEquals(0, resets)
        assertTrue(manager.state.value is AuthState.SignedIn)

        manager.resetAccount(confirmed = true)
        configRef.set(other)
        assertEquals(1, resets)
        assertTrue(manager.restore() is AuthState.SignedOut)
        assertNull(store.value)
        assertNull(bindings.value)

        val otherInput = TokenExchangeInput(other.clientId, "code", "verifier")
        val signedIn = manager.completeLogin(otherInput)
        assertEquals(OwnerIdentity.mask("owner"), signedIn.maskedUserId)
        assertEquals(other.clientId, store.value!!.clientId)
    }

    @Test fun `login rejects owner mismatch against the configured runtime owner`() = runBlocking {
        val store = MemoryStore()
        val manager = manager(store, identity = IdentityVerifier { _, _ -> VerifiedIdentity("someone-else", "acct-x") })
        assertEquals(AuthFailure.ACCOUNT_MISMATCH, failureOf { manager.completeLogin(input) }.reason)
        assertNull(store.value)
    }

    @Test fun `port in use maps to production failure`() = runBlocking {
        val flow = AuthorizationFlow(config, { throw ProbeException(ProbeFailure.PORT_IN_USE) },
            { PkceSession.create(it) }, Dispatchers.Unconfined)
        assertEquals(AuthFailure.PORT_IN_USE, failureOf { flow.authorize { } }.reason)
    }

    private fun manager(
        store: CredentialStore,
        tokens: FakeTokens = FakeTokens(),
        bindings: AccountBindingStore = MemoryBindings(),
        identity: IdentityVerifier = asAccount("acct-1"),
        resetSteps: List<AccountResetStep> = emptyList(),
    ) = SpotifyAuthManager(config, store, bindings, tokens, identity, resetSteps, { clock }, Dispatchers.Unconfined)

    private fun asAccount(accountId: String) = IdentityVerifier { _, owner -> VerifiedIdentity(owner, accountId) }

    private class MemoryBindings(var value: AccountBinding? = null) : AccountBindingStore {
        var failLoad = false
        override fun load(): AccountBinding? = if (failLoad) throw AuthException(AuthFailure.STORAGE) else value
        override fun save(binding: AccountBinding) { value = binding; failLoad = false }
        override fun clear() { value = null; failLoad = false }
    }

    private fun flow(listener: CallbackListener) =
        AuthorizationFlow(config, { listener }, { PkceSession.create(it) }, Dispatchers.IO)

    private fun credentials(
        clientId: String = DUMMY_CLIENT_ID,
        owner: String = "owner",
        expiresAt: Long = clock + 3_600_000,
    ) = StoredCredentials(clientId, owner, "acct-1", "stored-access", "stored-refresh", expiresAt, SpotifyAuth.scopes)

    private suspend fun failureOf(block: suspend () -> Unit): AuthException {
        try { block() } catch (failure: AuthException) { return failure }
        throw AssertionError("Expected safe auth failure")
    }

    private class MemoryStore(var value: StoredCredentials? = null) : CredentialStore {
        var saves = 0
        var failLoad = false
        var failClear = false
        override fun load(): StoredCredentials? = if (failLoad) throw AuthException(AuthFailure.STORAGE) else value
        override fun save(credentials: StoredCredentials) { saves++; value = credentials }
        override fun clear() { if (failClear) throw AuthException(AuthFailure.STORAGE); value = null; failLoad = false }
    }

    private class FakeTokens(
        private val gate: CompletableDeferred<Unit>? = null,
        var refreshFailure: AuthException? = null,
    ) : TokenEndpoint {
        val refreshes = AtomicInteger()
        val refreshInputs = mutableListOf<String>()
        var rotate = true
        private val active = AtomicInteger()
        @Volatile var maxConcurrent = 0

        override suspend fun exchange(input: TokenExchangeInput) =
            TokenResponse("access-0", "refresh-0", 3600, SpotifyAuth.scopes)

        override suspend fun refresh(clientId: String, refreshToken: String): TokenResponse {
            refreshFailure?.let { throw it }
            val n = refreshes.incrementAndGet()
            maxConcurrent = maxOf(maxConcurrent, active.incrementAndGet())
            try {
                gate?.await()
                synchronized(refreshInputs) { refreshInputs += refreshToken }
                return TokenResponse("access-$n", if (rotate) "refresh-$n" else null, 3600, emptySet())
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private class BlockingListener(private val result: TokenExchangeInput) : CallbackListener {
        val release = CountDownLatch(1)
        @Volatile var closed = false
        @Volatile var waiting = false

        override fun await(session: PkceSession): TokenExchangeInput {
            waiting = true
            while (!closed) {
                if (release.await(10, TimeUnit.MILLISECONDS)) return result
            }
            throw ProbeException(ProbeFailure.CANCELLED)
        }

        override fun close() { closed = true }
    }
}
