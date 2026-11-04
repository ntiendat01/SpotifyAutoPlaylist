package com.spotifyautoplaylist.auth

import java.net.URI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface AuthState {
    data object Restoring : AuthState
    data class SignedOut(val reason: AuthFailure? = null) : AuthState
    data class SignedIn(val maskedUserId: String) : AuthState
}

/**
 * Single owner of the persisted Spotify grant. Every read, refresh, login and sign-out runs under one
 * mutex, so concurrent callers share one refresh and a rotated refresh token is never lost.
 */
class SpotifyAuthManager(
    private val config: AuthConfig,
    private val store: CredentialStore,
    private val bindings: AccountBindingStore,
    private val tokens: TokenEndpoint,
    private val identity: IdentityVerifier,
    /** Ordered: stop background work first, then delete account-scoped data. */
    private val resetSteps: List<AccountResetStep> = emptyList(),
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var loaded = false
    private var current: StoredCredentials? = null
    // Access token issued by a refresh that a 401 triggered; a second 401 for it requires login.
    private var refreshedAfterUnauthorized: String? = null
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Restoring)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    suspend fun restore(): AuthState = mutex.withLock {
        ensureLoaded()
        state.value
    }

    /** Valid bearer token, refreshing first when it expires within [EXPIRY_MARGIN_MILLIS]. */
    suspend fun accessToken(): AccessToken = mutex.withLock {
        val credentials = activeLocked()
        if (credentials.expiresAtEpochMillis - now() > EXPIRY_MARGIN_MILLIS) AccessToken(credentials.accessToken)
        else AccessToken(refreshLocked(credentials).accessToken)
    }

    /**
     * Call once after an API 401 with the token that was rejected. Returns a newer token if another caller
     * already refreshed; otherwise refreshes once. A repeated 401 after that refresh signs out.
     */
    suspend fun onUnauthorized(rejected: AccessToken): AccessToken = mutex.withLock {
        val credentials = activeLocked()
        if (credentials.accessToken != rejected.value) return@withLock AccessToken(credentials.accessToken)
        if (refreshedAfterUnauthorized == credentials.accessToken) {
            discardLocked(AuthFailure.REAUTH_REQUIRED)
            throw AuthException(AuthFailure.REAUTH_REQUIRED, 401)
        }
        val refreshed = refreshLocked(credentials)
        refreshedAfterUnauthorized = refreshed.accessToken
        AccessToken(refreshed.accessToken)
    }

    /** Key for account-scoped data; throws unless the bound account is signed in. */
    suspend fun requireAccount(): AccountKey = mutex.withLock {
        val credentials = activeLocked()
        AccountKey(credentials.spotifyUserId, accountHash(credentials.accountId))
    }

    /**
     * Exchanges the code, verifies AUTH-006 identity and the local data binding, and only then persists
     * the grant. A different account than the one bound to local data is rejected until [resetAccount].
     */
    suspend fun completeLogin(input: TokenExchangeInput): AuthState.SignedIn = mutex.withLock {
        ensureLoaded()
        if (input.clientId != config.clientId) throw AuthException(AuthFailure.NOT_CONFIGURED)
        val bound = withContext(io) { bindings.load() }
        if (bound?.resetPending == true) throw AuthException(AuthFailure.RESET_PENDING)
        val response = tokens.exchange(input)
        val verified = identity.verify(AccessToken(response.accessToken), config.expectedOwnerId)
        if (verified.spotifyUserId != config.expectedOwnerId) throw AuthException(AuthFailure.ACCOUNT_MISMATCH, 200)
        val hash = accountHash(verified.accountId)
        if (bound != null && bound.accountHash != hash) throw AuthException(AuthFailure.ACCOUNT_MISMATCH, 200)
        current?.let { if (it.accountId != verified.accountId) throw AuthException(AuthFailure.ACCOUNT_MISMATCH, 200) }
        val credentials = StoredCredentials(
            config.clientId, verified.spotifyUserId, verified.accountId, response.accessToken,
            checkNotNull(response.refreshToken), now() + response.expiresInSeconds * 1_000L, response.scopes,
        )
        // Bind before saving the grant, so a stored grant always has an owner binding.
        if (bound == null) withContext(io) { bindings.save(AccountBinding(hash, resetPending = false)) }
        withContext(io) { store.save(credentials) }
        current = credentials
        refreshedAfterUnauthorized = null
        AuthState.SignedIn(verified.maskedUserId).also { mutableState.value = it }
    }

    /**
     * Explicit account change / "delete account data" (AUTH-006). Marks the reset durably, removes the grant,
     * runs [resetSteps], then removes the binding. If interrupted, the installation stays blocked
     * ([AuthFailure.RESET_PENDING]) until this is called again; it never falls back to the old account.
     */
    suspend fun resetAccount(confirmed: Boolean): Unit = mutex.withLock {
        if (!confirmed) throw AuthException(AuthFailure.CONFIRMATION_REQUIRED)
        withContext(NonCancellable) {
            loaded = true
            current = null
            refreshedAfterUnauthorized = null
            mutableState.value = AuthState.SignedOut(AuthFailure.RESET_PENDING)
            try {
                withContext(io) {
                    val previous = try { bindings.load()?.accountHash } catch (_: AuthException) { null }
                    bindings.save(AccountBinding(previous, resetPending = true))
                    store.clear()
                }
                resetSteps.forEach { it.reset() }
                withContext(io) { bindings.clear() }
            } catch (failure: Exception) {
                throw failure as? AuthException ?: AuthException(AuthFailure.UNEXPECTED)
            }
            mutableState.value = AuthState.SignedOut()
        }
    }

    /**
     * Removes the local grant but keeps the account binding ("keep local history"), so only the same
     * account can reconnect. Spotify offers no PKCE revocation endpoint; access is revoked in account settings.
     */
    suspend fun signOut(): Unit = mutex.withLock {
        loaded = true
        current = null
        refreshedAfterUnauthorized = null
        try {
            withContext(NonCancellable + io) { store.clear() }
            mutableState.value = AuthState.SignedOut()
        } catch (failure: AuthException) {
            // The file may survive and be restored on next launch; report it instead of claiming success.
            mutableState.value = AuthState.SignedOut(AuthFailure.STORAGE)
            throw failure
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val stored = try {
            withContext(io) { store.load() }
        } catch (_: AuthException) {
            // Unreadable grant (e.g. Keystore key invalidated): discard it; the user must reconnect.
            try { withContext(io) { store.clear() } } catch (_: AuthException) { /* still signed out */ }
            null.also { mutableState.value = AuthState.SignedOut(AuthFailure.STORAGE) }
        }
        val bound = try {
            withContext(io) { bindings.load() }
        } catch (_: AuthException) {
            // Never clear an unreadable binding: that would let another account adopt local data.
            loaded = true
            mutableState.value = AuthState.SignedOut(AuthFailure.STORAGE)
            return
        }
        loaded = true
        val storedHash = stored?.let { accountHash(it.accountId) }
        when {
            bound?.resetPending == true -> mutableState.value = AuthState.SignedOut(AuthFailure.RESET_PENDING)
            stored == null -> if (state.value == AuthState.Restoring) mutableState.value = AuthState.SignedOut()
            // A grant for another Client ID/owner/bound account is never used; only resetAccount clears it.
            stored.clientId != config.clientId || stored.spotifyUserId != config.expectedOwnerId ||
                (bound != null && bound.accountHash != storedHash) ->
                mutableState.value = AuthState.SignedOut(AuthFailure.ACCOUNT_MISMATCH)
            else -> {
                if (bound == null) {
                    try {
                        withContext(io) { bindings.save(AccountBinding(storedHash, resetPending = false)) }
                    } catch (_: AuthException) {
                        mutableState.value = AuthState.SignedOut(AuthFailure.STORAGE)
                        return
                    }
                }
                current = stored
                mutableState.value = AuthState.SignedIn(OwnerIdentity.mask(stored.spotifyUserId))
            }
        }
    }

    private suspend fun activeLocked(): StoredCredentials {
        ensureLoaded()
        current?.let { return it }
        val reason = (state.value as? AuthState.SignedOut)?.reason
        throw AuthException(
            if (reason == AuthFailure.ACCOUNT_MISMATCH || reason == AuthFailure.RESET_PENDING) reason
            else AuthFailure.REAUTH_REQUIRED,
        )
    }

    private suspend fun refreshLocked(credentials: StoredCredentials): StoredCredentials =
        // Spotify may invalidate the old refresh token as soon as it answers, so the request and the
        // save of its result must not be split by cancellation. OkHttp's call timeout bounds this.
        withContext(NonCancellable) {
            val response = try {
                tokens.refresh(config.clientId, credentials.refreshToken)
            } catch (failure: AuthException) {
                if (failure.reason == AuthFailure.REAUTH_REQUIRED || failure.reason == AuthFailure.INSUFFICIENT_SCOPE) {
                    discardLocked(failure.reason)
                }
                throw failure
            }
            val refreshed = credentials.refreshed(response, now())
            current = refreshed
            refreshedAfterUnauthorized = null
            // If this fails the new grant still serves this process; the next launch may need a login.
            withContext(io) { store.save(refreshed) }
            refreshed
        }

    private suspend fun discardLocked(reason: AuthFailure) {
        current = null
        refreshedAfterUnauthorized = null
        mutableState.value = AuthState.SignedOut(reason)
        try { withContext(io) { store.clear() } } catch (_: AuthException) { /* already unusable in memory */ }
    }

    companion object {
        const val EXPIRY_MARGIN_MILLIS = 60_000L
    }
}

/** Binds the loopback listener before opening the browser, and closes it on any exit. */
class AuthorizationFlow internal constructor(
    private val config: AuthConfig,
    private val listeners: () -> CallbackListener,
    private val sessions: (String) -> PkceSession,
    private val io: CoroutineDispatcher,
) {
    constructor(config: AuthConfig) :
        this(config, { LoopbackCallbackServer.bind() }, { PkceSession.create(it) }, Dispatchers.IO)

    suspend fun authorize(openBrowser: suspend (URI) -> Unit): TokenExchangeInput {
        try {
            val session = sessions(config.clientId)
            val listener = withContext(io) { listeners() }
            try {
                return coroutineScope {
                    val callback = async(io) { listener.await(session) }
                    try {
                        openBrowser(session.authorizationUri)
                        callback.await()
                    } catch (failure: Throwable) {
                        // Unblocks the socket accept so cancellation cannot wait for the 3-minute timeout.
                        listener.close()
                        throw failure
                    }
                }
            } finally {
                listener.close()
            }
        } catch (failure: ProbeException) {
            // The closed listener reports CANCELLED; the caller's cancellation must win over it.
            currentCoroutineContext().ensureActive()
            throw failure.toAuth()
        }
    }
}
