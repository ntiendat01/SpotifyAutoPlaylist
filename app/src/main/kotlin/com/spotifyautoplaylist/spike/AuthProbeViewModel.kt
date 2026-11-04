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

enum class ProbePhase { IDLE, STARTING, WAITING, EXCHANGING, VERIFYING_ACCOUNT, SUCCEEDED, FAILED }

data class ProbeUiState(
    val clientId: String = "",
    val expectedOwnerId: String = "",
    val phase: ProbePhase = ProbePhase.IDLE,
    val failure: ProbeFailure? = null,
    val result: TokenProbeResult? = null,
    val account: AccountProbeResult? = null,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null,
) {
    val busy: Boolean get() = phase in setOf(
        ProbePhase.STARTING, ProbePhase.WAITING, ProbePhase.EXCHANGING, ProbePhase.VERIFYING_ACCOUNT,
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

    fun editClientId(value: String) {
        if (!state.value.busy) mutableState.value = ProbeUiState(
            clientId = value.trim(), expectedOwnerId = state.value.expectedOwnerId,
        )
    }

    fun editExpectedOwnerId(value: String) {
        if (!state.value.busy) mutableState.value = ProbeUiState(
            clientId = state.value.clientId, expectedOwnerId = value.trim(),
        )
    }

    fun start() {
        if (state.value.busy) return
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
                exchange.verify(input).use { grant ->
                    ensureActive()
                    mutableState.value = state.value.copy(phase = ProbePhase.VERIFYING_ACCOUNT)
                    val account = accounts.verify(grant, ownerId)
                    ensureActive()
                    mutableState.value = state.value.copy(
                        phase = ProbePhase.SUCCEEDED, result = grant.metadata, account = account,
                    )
                }
            } catch (_: TimeoutCancellationException) {
                mutableState.value = state.value.copy(phase = ProbePhase.FAILED, failure = ProbeFailure.CALLBACK_TIMEOUT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ProbeException) {
                if (failure.httpStatus == 429) {
                    retryFailure = failure.reason
                    retryUntilNanos = nanoTime() + (failure.retryAfterSeconds ?: 60) * 1_000_000_000
                }
                mutableState.value = state.value.copy(
                    phase = ProbePhase.FAILED, failure = failure.reason, httpStatus = failure.httpStatus,
                    retryAfterSeconds = failure.retryAfterSeconds,
                )
            } catch (_: Exception) {
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
        mutableState.value = state.value.copy(phase = ProbePhase.FAILED, failure = reason)
    }

    override fun onCleared() {
        job?.cancel()
        listener?.close()
        browserChannel.close()
    }
}
