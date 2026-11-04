package com.spotifyautoplaylist.spike

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifyautoplaylist.auth.CallbackListener
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

enum class ProbePhase { IDLE, STARTING, WAITING, EXCHANGING, SUCCEEDED, FAILED }

data class ProbeUiState(
    val clientId: String = "",
    val phase: ProbePhase = ProbePhase.IDLE,
    val failure: ProbeFailure? = null,
    val result: TokenProbeResult? = null,
    val httpStatus: Int? = null,
) {
    val busy: Boolean get() = phase in setOf(ProbePhase.STARTING, ProbePhase.WAITING, ProbePhase.EXCHANGING)
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
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProbeUiState())
    val state = mutableState.asStateFlow()
    private val browserChannel = Channel<BrowserRequest>(Channel.RENDEZVOUS)
    val browserRequests = browserChannel.receiveAsFlow()
    private var job: Job? = null
    // Both writes and explicit close happen on Main. The listener itself is thread-safe.
    private var listener: CallbackListener? = null

    fun editClientId(value: String) {
        if (!state.value.busy) mutableState.value = ProbeUiState(clientId = value.trim())
    }

    fun start() {
        if (state.value.busy) return
        val clientId = state.value.clientId
        if (!SpotifyAuth.isValidClientId(clientId)) {
            mutableState.value = state.value.copy(phase = ProbePhase.FAILED, failure = ProbeFailure.INVALID_CLIENT_ID)
            return
        }
        mutableState.value = ProbeUiState(clientId = clientId, phase = ProbePhase.STARTING)
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
                val result = exchange.verify(input)
                ensureActive()
                mutableState.value = state.value.copy(phase = ProbePhase.SUCCEEDED, result = result)
            } catch (_: TimeoutCancellationException) {
                mutableState.value = state.value.copy(phase = ProbePhase.FAILED, failure = ProbeFailure.CALLBACK_TIMEOUT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ProbeException) {
                mutableState.value = state.value.copy(
                    phase = ProbePhase.FAILED, failure = failure.reason, httpStatus = failure.httpStatus,
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
