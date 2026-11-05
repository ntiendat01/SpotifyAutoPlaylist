package com.spotifyautoplaylist.spike

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifyautoplaylist.auth.CallbackListener
import com.spotifyautoplaylist.auth.AccountProbe
import com.spotifyautoplaylist.auth.AccountProbeResult
import com.spotifyautoplaylist.auth.AccountVerification
import com.spotifyautoplaylist.auth.OwnerIdentity
import com.spotifyautoplaylist.auth.LoopbackCallbackServer
import com.spotifyautoplaylist.auth.PkceSession
import com.spotifyautoplaylist.auth.ProbeException
import com.spotifyautoplaylist.auth.ProbeFailure
import com.spotifyautoplaylist.auth.SpotifyAuth
import com.spotifyautoplaylist.auth.TokenExchange
import com.spotifyautoplaylist.auth.TokenProbe
import com.spotifyautoplaylist.auth.TokenProbeResult
import com.spotifyautoplaylist.auth.SmokeSession
import com.spotifyautoplaylist.auth.SmokeSessionFactory
import com.spotifyautoplaylist.auth.SmokeView
import com.spotifyautoplaylist.auth.SmokeReport
import com.spotifyautoplaylist.auth.RecentSmokeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class ProbePhase { IDLE, STARTING, WAITING, EXCHANGING, VERIFYING_ACCOUNT, RUNNING_API, SUCCEEDED, FAILED }

enum class SmokeAction { RECENT, CREATE, LINK, ADD, RECONCILE }

data class ProbeUiState(
    val clientId: String = "",
    val expectedOwnerId: String = "",
    val phase: ProbePhase = ProbePhase.IDLE,
    val failure: ProbeFailure? = null,
    val result: TokenProbeResult? = null,
    val account: AccountProbeResult? = null,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null,
    val sessionReady: Boolean = false,
    val smoke: SmokeView? = null,
    val recent: RecentSmokeResult? = null,
    val report: SmokeReport? = null,
    val trackInput: String = "",
    val playlistInput: String = "",
    val activeAction: SmokeAction? = null,
) {
    val busy: Boolean get() = phase in setOf(
        ProbePhase.STARTING, ProbePhase.WAITING, ProbePhase.EXCHANGING, ProbePhase.VERIFYING_ACCOUNT, ProbePhase.RUNNING_API,
    )
    // Client ID is public but avoid accidentally adding configuration values to diagnostic logs.
    override fun toString() = "ProbeUiState(phase=$phase, failure=$failure, httpStatus=$httpStatus)"
}

class BrowserRequest internal constructor(val uri: String) {
    override fun toString() = "BrowserRequest(<redacted>)"
}

class AuthProbeViewModel(
    private val listenerFactory: () -> CallbackListener = { LoopbackCallbackServer.bind() },
    private val exchange: TokenExchange = TokenProbe(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val accounts: AccountVerification = AccountProbe(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val smokeFactory: SmokeSessionFactory? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProbeUiState())
    val state = mutableState.asStateFlow()
    private val browserChannel = Channel<BrowserRequest>(Channel.RENDEZVOUS)
    val browserRequests = browserChannel.receiveAsFlow()
    private var job: Job? = null
    // Both writes and explicit close happen on Main. The listener itself is thread-safe.
    private var listener: CallbackListener? = null
    private var retryUntilNanos: Long? = null
    private var retryFailure = ProbeFailure.PROFILE_HTTP
    private var activeSession: SmokeSession? = null

    fun editClientId(value: String) {
        if (!state.value.busy && !state.value.sessionReady) mutableState.value = ProbeUiState(
            clientId = value.trim(), expectedOwnerId = state.value.expectedOwnerId,
        )
    }

    fun editExpectedOwnerId(value: String) {
        if (!state.value.busy && !state.value.sessionReady) mutableState.value = ProbeUiState(
            clientId = state.value.clientId, expectedOwnerId = value.trim(),
        )
    }

    fun start() {
        if (state.value.busy || state.value.sessionReady) return
        val retryRemaining = retryUntilNanos?.let { it - nanoTime() } ?: 0L
        if (retryRemaining > 0) {
            mutableState.value = state.value.copy(
                phase = ProbePhase.FAILED, failure = retryFailure, httpStatus = 429,
                retryAfterSeconds = (retryRemaining + 999_999_999) / 1_000_000_000,
            )
            return
        }
        val clientId = state.value.clientId
        val ownerId = state.value.expectedOwnerId
        if (!SpotifyAuth.isValidClientId(clientId)) {
            mutableState.value = state.value.copy(phase = ProbePhase.FAILED, failure = ProbeFailure.INVALID_CLIENT_ID)
            return
        }
        if (!OwnerIdentity.isValid(ownerId)) {
            mutableState.value = state.value.copy(phase = ProbePhase.FAILED, failure = ProbeFailure.INVALID_OWNER_ID)
            return
        }
        mutableState.value = ProbeUiState(clientId = clientId, expectedOwnerId = ownerId, phase = ProbePhase.STARTING)
        job = viewModelScope.launch {
            var ownListener: CallbackListener? = null
            try {
                val session = PkceSession.create(clientId)
                // Retain ownership inside IO before returning: if cancellation wins the
                // dispatcher handoff, finally still closes the successfully bound socket.
                val boundListener = withContext(ioDispatcher) {
                    listenerFactory().also { ownListener = it }
                }
                listener = boundListener
                mutableState.value = state.value.copy(phase = ProbePhase.WAITING)
                val input = withTimeout(SpotifyAuth.TIMEOUT_MILLIS) {
                    browserChannel.send(BrowserRequest(session.authorizationUri.toASCIIString()))
                    withContext(ioDispatcher) { boundListener.await(session) }
                }
                ensureActive()
                mutableState.value = state.value.copy(phase = ProbePhase.EXCHANGING)
                val grant = exchange.verify(input)
                var ownedSession: SmokeSession? = null
                var retained = false
                try {
                    ensureActive()
                    mutableState.value = state.value.copy(phase = ProbePhase.VERIFYING_ACCOUNT)
                    val account = if (smokeFactory == null) accounts.verify(grant, ownerId) else {
                        smokeFactory.open(grant, ownerId, clientId).also { ownedSession = it }.account
                    }
                    ensureActive()
                    activeSession = ownedSession
                    mutableState.value = state.value.copy(
                        phase = ProbePhase.SUCCEEDED, result = grant.metadata, account = account,
                        sessionReady = ownedSession != null, smoke = ownedSession?.snapshot(),
                    )
                    retained = ownedSession != null
                } finally {
                    if (!retained) { ownedSession?.close(); grant.close() }
                }
            } catch (_: TimeoutCancellationException) {
                mutableState.value = state.value.copy(phase = ProbePhase.FAILED, failure = ProbeFailure.CALLBACK_TIMEOUT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ProbeException) {
                ensureActive()
                if (failure.httpStatus == 429) {
                    retryFailure = failure.reason
                    retryUntilNanos = nanoTime() + (failure.retryAfterSeconds ?: 60) * 1_000_000_000
                }
                mutableState.value = state.value.copy(
                    phase = ProbePhase.FAILED, failure = failure.reason, httpStatus = failure.httpStatus,
                    retryAfterSeconds = failure.retryAfterSeconds,
                )
            } catch (_: Exception) {
                ensureActive()
                mutableState.value = state.value.copy(phase = ProbePhase.FAILED, failure = ProbeFailure.NETWORK)
            } finally {
                ownListener?.close()
                if (listener === ownListener) listener = null
            }
        }
    }

    fun cancel(reason: ProbeFailure = ProbeFailure.CANCELLED) {
        if (!state.value.busy) return
        job?.cancel()
        listener?.close()
        listener = null
        activeSession?.close()
        activeSession = null
        mutableState.value = state.value.copy(phase = ProbePhase.FAILED, failure = reason,
            sessionReady = false, report = null, smoke = null)
    }

    fun endSession() {
        job?.cancel()
        activeSession?.close()
        activeSession = null
        mutableState.value = ProbeUiState(clientId = state.value.clientId, expectedOwnerId = state.value.expectedOwnerId)
    }

    fun editTrack(value: String) {
        if (!state.value.busy) mutableState.value = state.value.copy(trackInput = value, report = null)
    }

    fun editPlaylist(value: String) {
        if (!state.value.busy) mutableState.value = state.value.copy(playlistInput = value, report = null)
    }

    fun runSmoke(action: SmokeAction, confirmed: Boolean = false, input: String? = null) {
        if (state.value.busy) return
        val session = activeSession ?: return
        val track = input ?: state.value.trackInput
        val playlist = input ?: state.value.playlistInput
        mutableState.value = state.value.copy(phase = ProbePhase.RUNNING_API, activeAction = action,
            failure = null, httpStatus = null, retryAfterSeconds = null, report = null)
        job = viewModelScope.launch {
            try {
                val report = when (action) {
                    SmokeAction.RECENT -> {
                        val recent = session.recent()
                        ensureActive()
                        mutableState.value = state.value.copy(recent = recent)
                        null
                    }
                    SmokeAction.CREATE -> session.preparePlaylist(confirmed)
                    SmokeAction.LINK -> session.linkPlaylist(playlist, confirmed)
                    SmokeAction.ADD -> session.addTrack(track, confirmed)
                    SmokeAction.RECONCILE -> session.reconcile()
                }
                ensureActive()
                mutableState.value = state.value.copy(phase = ProbePhase.SUCCEEDED, report = report,
                    smoke = session.snapshot(), activeAction = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ProbeException) {
                ensureActive()
                if (failure.httpStatus == 429) {
                    retryFailure = failure.reason
                    retryUntilNanos = nanoTime() + (failure.retryAfterSeconds ?: 60) * 1_000_000_000
                }
                val close = failure.httpStatus == 401 || failure.reason in setOf(
                    ProbeFailure.SESSION_EXPIRED, ProbeFailure.QUOTA_EXCEEDED, ProbeFailure.JOURNAL,
                )
                if (close) { session.close(); if (activeSession === session) activeSession = null }
                mutableState.value = state.value.copy(phase = ProbePhase.FAILED, failure = failure.reason,
                    httpStatus = failure.httpStatus, retryAfterSeconds = failure.retryAfterSeconds,
                    sessionReady = !close, smoke = if (close) null else session.snapshot(), activeAction = null)
            } catch (_: Exception) {
                ensureActive()
                session.close()
                if (activeSession === session) activeSession = null
                mutableState.value = state.value.copy(phase = ProbePhase.FAILED, failure = ProbeFailure.API_RESPONSE,
                    sessionReady = false, smoke = null, activeAction = null)
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
        listener?.close()
        activeSession?.close()
        browserChannel.close()
    }
}
