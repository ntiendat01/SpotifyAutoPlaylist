package com.spotifyautoplaylist.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.auth.AuthFailure
import com.spotifyautoplaylist.auth.AuthState
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface OnboardingAuth {
    val state: StateFlow<AuthState>
    suspend fun restore(): AuthState
    suspend fun login(openBrowser: suspend (URI) -> Unit, verifying: () -> Unit)
    suspend fun signOut()
}

enum class OnboardingStage { RESTORING, WELCOME, WAITING, VERIFYING, SETUP, READY }

data class OnboardingUiState(
    val stage: OnboardingStage = OnboardingStage.RESTORING,
    val busy: Boolean = false,
    val configured: Boolean = true,
    val error: String? = null,
    val playlistName: String = DEFAULT_PLAYLIST_NAME,
    val playlistLink: String = "",
    val playlist: SetupPlaylist? = null,
    val recoveryRequired: Boolean = false,
) {
    override fun toString() = "OnboardingUiState(stage=$stage, busy=$busy, <redacted>)"
}

/** A lifecycle collector must acknowledge browser launch; cancellation invalidates queued requests. */
class BrowserRequest(val uri: URI, val completion: CompletableDeferred<Boolean>) {
    override fun toString() = "BrowserRequest(<redacted>)"
}

class OnboardingViewModel(private val auth: OnboardingAuth?, private val setup: PlaylistSetup?) : ViewModel() {
    private val mutable = MutableStateFlow(OnboardingUiState(configured = auth != null))
    val state = mutable.asStateFlow()
    private val browser = Channel<BrowserRequest>(Channel.RENDEZVOUS)
    val browserRequests = browser.receiveAsFlow()
    private var operation: Job? = null

    init { restore() }

    fun restore() = runOperation {
        val currentAuth = auth
        if (currentAuth == null) mutable.value = OnboardingUiState(stage = OnboardingStage.WELCOME, configured = false)
        else when (val restored = currentAuth.restore()) {
            is AuthState.SignedIn -> showSetup()
            is AuthState.SignedOut -> mutable.value = mutable.value.copy(stage = OnboardingStage.WELCOME,
                error = restored.reason?.let { authMessage(AuthException(it)) })
            AuthState.Restoring -> error("restore incomplete")
        }
    }

    fun connect() {
        val currentAuth = auth ?: return
        if (state.value.stage != OnboardingStage.WELCOME) return
        runOperation {
            mutable.value = mutable.value.copy(stage = OnboardingStage.WAITING, error = null)
            currentAuth.login(openBrowser = { uri ->
                val acknowledgment = CompletableDeferred<Boolean>(currentCoroutineContext()[Job])
                browser.send(BrowserRequest(uri, acknowledgment))
                if (!acknowledgment.await()) throw AuthException(AuthFailure.BROWSER_UNAVAILABLE)
            }, verifying = { mutable.value = mutable.value.copy(stage = OnboardingStage.VERIFYING) })
            showSetup()
        }
    }

    fun cancelLogin() {
        if (state.value.stage == OnboardingStage.WAITING) operation?.cancel()
    }

    fun editName(value: String) { if (!state.value.busy) mutable.value = mutable.value.copy(playlistName = value, error = null) }
    fun editLink(value: String) { if (!state.value.busy) mutable.value = mutable.value.copy(playlistLink = value, error = null) }

    fun create(confirmed: Boolean) {
        if (state.value.stage != OnboardingStage.SETUP) return
        runOperation {
            val playlist = requireNotNull(setup).create(state.value.playlistName, confirmed)
            mutable.value = mutable.value.copy(stage = OnboardingStage.READY, playlist = playlist)
        }
    }

    fun link(confirmed: Boolean) {
        if (state.value.stage != OnboardingStage.SETUP) return
        runOperation {
            val playlist = requireNotNull(setup).link(state.value.playlistLink, confirmed)
            mutable.value = mutable.value.copy(stage = OnboardingStage.READY, playlist = playlist, playlistLink = "")
        }
    }

    fun recover() {
        if (state.value.stage != OnboardingStage.SETUP) return
        runOperation {
            val playlist = requireNotNull(setup).recover()
            mutable.value = mutable.value.copy(stage = OnboardingStage.READY, playlist = playlist)
        }
    }

    fun disconnect() = runOperation {
        requireNotNull(auth).signOut()
        mutable.value = OnboardingUiState(stage = OnboardingStage.WELCOME)
    }

    private suspend fun showSetup() {
        val snapshot = requireNotNull(setup).load()
        mutable.value = mutable.value.copy(
            stage = if (snapshot.playlist == null) OnboardingStage.SETUP else OnboardingStage.READY,
            playlist = snapshot.playlist, recoveryRequired = snapshot.recoveryRequired, error = null,
        )
    }

    private fun runOperation(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) {
                // Login cancellation must release the listener before another attempt is allowed.
                mutable.value = mutable.value.copy(stage = OnboardingStage.WELCOME, error = "Đã hủy đăng nhập. Bạn có thể kết nối lại.")
                throw cancelled
            } catch (failure: Exception) {
                val signedIn = auth?.state?.value is AuthState.SignedIn
                val recovery = if (signedIn) withContext(NonCancellable) {
                    try { setup?.load()?.recoveryRequired ?: true } catch (_: Exception) { true }
                } else false
                mutable.value = mutable.value.copy(
                    stage = if (signedIn) OnboardingStage.SETUP else OnboardingStage.WELCOME,
                    playlist = null, recoveryRequired = recovery, error = userMessage(failure),
                )
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}

internal fun userMessage(failure: Exception): String = when (failure) {
    is AuthException -> authMessage(failure)
    is SetupException -> when (failure.reason) {
        SetupFailure.CONFIRMATION -> "Hãy xác nhận trước khi tạo hoặc liên kết playlist."
        SetupFailure.INVALID_NAME -> "Tên playlist cần từ 1 đến 100 ký tự."
        SetupFailure.INVALID_LINK -> "Hãy dán link hoặc ID playlist Spotify hợp lệ."
        SetupFailure.OWNER -> "Playlist không thuộc tài khoản Spotify đã xác minh. Hãy chọn playlist của bạn."
        SetupFailure.PRIVACY -> "Playlist chưa được xác minh là riêng tư và không cộng tác. Kiểm tra trong Spotify rồi xác minh lại."
        SetupFailure.RESPONSE -> "Spotify chưa trả đủ thông tin để xác minh playlist. Hãy thử xác minh lại."
        SetupFailure.RECOVERY -> "Chưa tìm lại được playlist đã yêu cầu tạo. Kiểm tra thư viện Spotify rồi dán link playlist riêng tư để xác minh. Nếu chưa có playlist, tạo thủ công trong Spotify rồi dán link. App sẽ không tự tạo thêm."
        SetupFailure.STORAGE -> "Không đọc hoặc lưu an toàn được thiết lập. Đã dừng thao tác; giữ dữ liệu và thử mở lại app."
        SetupFailure.ALREADY_LINKED -> "App đã liên kết một playlist khác. Không thể tự thay thế."
    }
    is SpotifyApiException -> when (failure.kind) {
        SpotifyErrorKind.UNAUTHORIZED -> "Phiên Spotify đã hết hiệu lực. Hãy kết nối lại."
        SpotifyErrorKind.FORBIDDEN -> "Spotify chưa cho phép thao tác này. Kiểm tra quyền truy cập của tài khoản rồi thử lại."
        SpotifyErrorKind.NOT_FOUND -> "Không tìm thấy playlist. Kiểm tra trong Spotify rồi dán link đúng."
        SpotifyErrorKind.RATE_LIMITED -> "Spotify đang giới hạn yêu cầu. Thử lại sau ${failure.retryAfterSeconds ?: 60} giây."
        SpotifyErrorKind.QUOTA_EXCEEDED -> "Đã hết hạn mức Spotify API. Tạm dừng và thử lại khi hạn mức hồi phục."
        SpotifyErrorKind.NETWORK -> "Không kết nối được Spotify. Kiểm tra mạng rồi thử lại; nếu đã yêu cầu tạo playlist, hãy xác minh lại."
        else -> "Spotify chưa hoàn tất thao tác. Hãy thử xác minh lại; app không tự gửi lại yêu cầu tạo playlist."
    }
    else -> "Không hoàn tất được thao tác. Dữ liệu được giữ lại; hãy thử mở lại app."
}

internal fun authMessage(failure: AuthException): String = when (failure.reason) {
    AuthFailure.NOT_CONFIGURED -> "Bản cài này chưa được thiết lập kết nối Spotify. Bạn vẫn có thể xem thử giao diện."
    AuthFailure.PORT_IN_USE -> "Đang có phiên đăng nhập khác. Đóng phiên đó rồi thử lại."
    AuthFailure.CALLBACK_TIMEOUT -> "Đã hết thời gian chờ đăng nhập. Hãy thử lại."
    AuthFailure.CANCELLED -> "Đã hủy đăng nhập. Bạn có thể thử lại."
    AuthFailure.BROWSER_UNAVAILABLE -> "Không mở được trình duyệt. Kiểm tra trình duyệt mặc định rồi thử lại."
    AuthFailure.AUTHORIZATION_DENIED -> "Bạn chưa cấp quyền Spotify. Kết nối lại khi sẵn sàng."
    AuthFailure.ACCOUNT_MISMATCH -> "Tài khoản Spotify không khớp với tài khoản đã thiết lập. Đăng xuất Spotify trong trình duyệt rồi kết nối lại đúng tài khoản."
    AuthFailure.INSUFFICIENT_SCOPE -> "Spotify chưa cấp đủ quyền. Hãy kết nối lại và cấp các quyền được yêu cầu."
    AuthFailure.REAUTH_REQUIRED -> "Phiên Spotify đã hết hiệu lực. Hãy kết nối lại."
    AuthFailure.STORAGE -> "Không đọc được phiên đã lưu. Hãy kết nối lại; dữ liệu tài khoản vẫn được giữ riêng."
    AuthFailure.RESET_PENDING -> "Lần xóa dữ liệu tài khoản trước chưa hoàn tất. Cần hoàn tất khôi phục dữ liệu trước khi kết nối lại."
    AuthFailure.QUOTA_EXCEEDED -> "Đã hết hạn mức Spotify API. Tạm dừng kết nối và thử lại khi hạn mức hồi phục."
    AuthFailure.NETWORK -> "Không kết nối được Spotify. Kiểm tra mạng rồi thử lại."
    else -> if (failure.httpStatus == 429) "Spotify đang giới hạn đăng nhập. Thử lại sau ${failure.retryAfterSeconds ?: 60} giây."
        else "Chưa xác minh được kết nối Spotify. Hãy kiểm tra mạng và quyền truy cập rồi thử lại."
}
