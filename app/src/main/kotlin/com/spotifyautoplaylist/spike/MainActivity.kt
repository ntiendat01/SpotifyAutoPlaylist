package com.spotifyautoplaylist.spike

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.net.toUri
import com.spotifyautoplaylist.auth.ProbeFailure
import com.spotifyautoplaylist.auth.SpotifyAuth
import com.spotifyautoplaylist.auth.ApiSmokeFactory
import com.spotifyautoplaylist.auth.FileSmokeJournal
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent account/configuration input and browser-return snapshots from entering captures.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val model: AuthProbeViewModel = viewModel(factory = viewModelFactory {
                initializer {
                    AuthProbeViewModel(smokeFactory = ApiSmokeFactory(
                        FileSmokeJournal(File(applicationContext.noBackupFilesDir, "m0-smoke")),
                    ))
                }
            })
            val state by model.state.collectAsStateWithLifecycle()
            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(model, lifecycleOwner) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    model.browserRequests.collect { request ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, request.uri.toUri()).apply {
                                addCategory(Intent.CATEGORY_BROWSABLE)
                            })
                        } catch (_: ActivityNotFoundException) {
                            model.cancel(ProbeFailure.BROWSER_UNAVAILABLE)
                        } catch (_: SecurityException) {
                            model.cancel(ProbeFailure.BROWSER_UNAVAILABLE)
                        }
                    }
                }
            }
            BackHandler(enabled = state.busy) { model.cancel() }
            ReplayProbeTheme {
                AuthProbeScreen(state, model::editClientId, model::editExpectedOwnerId, model::start, { model.cancel() }, model)
            }
        }
    }
}

@Composable
private fun ReplayProbeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF1ED760), onPrimary = Color(0xFF06140C),
            background = Color(0xFF090D0B), onBackground = Color(0xFFF1FFF5),
            surface = Color(0xFF171F1A), onSurface = Color(0xFFF1FFF5),
            surfaceVariant = Color(0xFF223029), onSurfaceVariant = Color(0xFFA9B9AD),
            outline = Color(0xFF83958A), error = Color(0xFFFF7B80),
        ),
        content = content,
    )
}

@Composable
internal fun AuthProbeScreen(
    state: ProbeUiState,
    onClientIdChanged: (String) -> Unit,
    onOwnerIdChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    model: AuthProbeViewModel,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).imePadding()
                .background(Brush.verticalGradient(listOf(Color(0xFF10281B), Color(0xFF090D0B))))
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("replay.", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("THỬ NGHIỆM KẾT NỐI · M0", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge)
            Text("Nhạc bạn nghe lại.\nPlaylist tự tạo.", fontSize = 28.sp,
                lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
            Text("Xác minh tài khoản Spotify đã tạo Developer App.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            )) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Trước khi kết nối", style = MaterialTheme.typography.titleMedium)
                    Text("Dùng tài khoản Premium mới đã tạo Developer App. Đăng nhập trên trang Spotify; ứng dụng không nhận mật khẩu của bạn.")
                    Text("Trong Spotify Dashboard, đăng ký chính xác redirect URI:")
                    Text(SpotifyAuth.REDIRECT_URI, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(
                value = state.clientId, onValueChange = onClientIdChanged,
                modifier = Modifier.fillMaxWidth(), enabled = !state.busy && !state.sessionReady, singleLine = true,
                label = { Text("Client ID") },
                supportingText = { Text("32 ký tự từ Developer App. Không nhập Client Secret.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                isError = state.failure == ProbeFailure.INVALID_CLIENT_ID,
            )
            OutlinedTextField(
                value = state.expectedOwnerId, onValueChange = onOwnerIdChanged,
                modifier = Modifier.fillMaxWidth(), enabled = !state.busy && !state.sessionReady, singleLine = true,
                label = { Text("Spotify user ID của chủ Developer App") },
                supportingText = { Text("Lấy phần sau /user/ trong link hồ sơ của tài khoản chủ app, bỏ ?si=…; hoặc username trong trang tài khoản. Không dùng tên hiển thị, email hoặc Client ID.") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                isError = state.failure == ProbeFailure.INVALID_OWNER_ID,
            )
            Button(onClick = onConnect, enabled = !state.busy && !state.sessionReady,
                modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (state.phase == ProbePhase.IDLE) "Kết nối Spotify" else "Thử lại kết nối")
            }
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Hủy đăng nhập")
                }
            }
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusText(state), color = if (state.phase == ProbePhase.FAILED)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                state.result?.let { result ->
                    Text("OAuth HTTP ${result.httpStatus} · Đã xác nhận đủ ${SpotifyAuth.scopes.size} quyền yêu cầu.")
                }
                state.account?.let { account ->
                    Text("GET /me HTTP ${account.httpStatus} · ID ${account.maskedUserId} khớp ID đã nhập.")
                    Text("Danh tính đã khớp. Premium và quyền sở hữu Developer App vẫn cần bằng chứng riêng; playlist được kiểm tra ở bước dưới.")
                }
                state.retryAfterSeconds?.let { Text("Chờ khoảng $it giây trước khi thử lại. Không tự gửi lại yêu cầu.") }
            }
            if (state.sessionReady) SmokeControls(state, model)
            Spacer(Modifier.height(8.dp))
            Text("Bản thử M0 giữ token trong bộ nhớ cho phiên kiểm tra API, không lưu token hay lịch sử nghe. Chỉ lưu ID playlist/bài thử và trạng thái phục hồi trong bộ nhớ riêng của app. Playlist thật vẫn tồn tại sau khi gỡ app.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("Ứng dụng cá nhân độc lập, không phải ứng dụng chính thức của Spotify.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun statusText(state: ProbeUiState): String = when (state.phase) {
    ProbePhase.IDLE -> "Sẵn sàng kiểm tra."
    ProbePhase.STARTING -> "Đang chuẩn bị kết nối…"
    ProbePhase.WAITING -> "Đang chờ đăng nhập trong trình duyệt (tối đa 3 phút). Sau đó quay lại replay. Nếu đã đóng trình duyệt, chọn Hủy."
    ProbePhase.EXCHANGING -> "Đã nhận callback hợp lệ. Đang kiểm tra trao đổi token…"
    ProbePhase.VERIFYING_ACCOUNT -> "Đang xác minh tài khoản qua Spotify /me…"
    ProbePhase.RUNNING_API -> "Đang kiểm tra API Spotify… Không gửi thêm yêu cầu khi tác vụ chưa kết thúc."
    ProbePhase.SUCCEEDED -> "Danh tính Spotify khớp tài khoản đã thiết lập."
    ProbePhase.FAILED -> when (state.failure) {
        ProbeFailure.INVALID_CLIENT_ID -> "Client ID phải gồm đúng 32 ký tự hex."
        ProbeFailure.PORT_IN_USE -> "Cổng 8765 đang được sử dụng. Đóng phiên đăng nhập khác rồi thử lại."
        ProbeFailure.CALLBACK_TIMEOUT -> "Đã hết thời gian chờ. Hãy thử đăng nhập lại."
        ProbeFailure.CANCELLED -> "Đã dừng phiên. Nếu đã gửi thao tác ghi, kết quả có thể chưa rõ; đăng nhập lại và đối soát trước khi thử lại."
        ProbeFailure.AUTHORIZATION_DENIED -> "Bạn đã từ chối cấp quyền Spotify. Có thể thử lại."
        ProbeFailure.AUTHORIZATION_FAILED -> "Spotify không chấp nhận đăng nhập. Kiểm tra cấu hình Developer App."
        ProbeFailure.BROWSER_UNAVAILABLE -> "Không mở được trình duyệt. Kiểm tra trình duyệt mặc định rồi thử lại."
        ProbeFailure.TOKEN_HTTP -> if (state.httpStatus == 429)
            "Spotify đang giới hạn trao đổi token. Tạm dừng và thử lại sau thời gian chờ."
            else "Spotify từ chối trao đổi token (HTTP ${state.httpStatus}). Kiểm tra Client ID và redirect URI trước khi thử lại."
        ProbeFailure.TOKEN_RESPONSE -> "Phản hồi token không đúng cấu trúc mong đợi. Chưa xác nhận thành công."
        ProbeFailure.INSUFFICIENT_SCOPE -> "Spotify chưa cấp đủ ba quyền yêu cầu. Kiểm tra cấu hình Developer App."
        ProbeFailure.INVALID_OWNER_ID -> "Hãy nhập Spotify user ID của tài khoản đã tạo Developer App trước khi đăng nhập."
        ProbeFailure.ACCOUNT_MISMATCH -> "Tài khoản Spotify không khớp. Đã từ chối phiên này. Đăng xuất Spotify trong trình duyệt rồi kết nối lại đúng tài khoản; không thay ID để bỏ qua kiểm tra."
        ProbeFailure.PROFILE_RESPONSE -> "Spotify /me không trả về đủ định danh hợp lệ để kiểm tra API và lưu trạng thái phục hồi. Chưa cho phép tiếp tục."
        ProbeFailure.PROFILE_HTTP -> when (state.httpStatus) {
            401 -> "Phiên Spotify không còn hợp lệ. Hãy đăng nhập lại."
            403 -> "Spotify không cho phép đọc /me. Kiểm tra tài khoản và quyền truy cập Developer App."
            429 -> "Spotify đang giới hạn yêu cầu. Tạm dừng kiểm tra và thử lại sau thời gian chờ."
            else -> "Không xác minh được tài khoản qua /me (HTTP ${state.httpStatus}). Chưa cho phép tiếp tục."
        }
        ProbeFailure.SESSION_EXPIRED -> "Phiên xác minh đã kết thúc. Hãy đăng nhập lại."
        ProbeFailure.API_HTTP -> when (state.httpStatus) {
            401 -> "Phiên Spotify hết hiệu lực. Hãy kết nối lại."
            403 -> "Spotify không cho phép endpoint này. Kiểm tra quyền Development Mode; không tự thử lặp."
            404 -> "Không tìm thấy playlist hoặc tài nguyên. Kiểm tra trong Spotify; không tự tạo lại."
            429 -> "Spotify đang giới hạn yêu cầu. Chờ trước khi thử lại."
            else -> "API Spotify chưa thành công (HTTP ${state.httpStatus}). Kết quả ghi có thể cần đối soát."
        }
        ProbeFailure.API_RESPONSE -> "Phản hồi API không đúng cấu trúc mong đợi. Không xác nhận đã thêm; hãy đối soát lại."
        ProbeFailure.INVALID_TRACK -> "Nhập ID 22 ký tự hoặc link Spotify của một bài hát (track)."
        ProbeFailure.INVALID_PLAYLIST -> "Nhập ID 22 ký tự hoặc link playlist Spotify."
        ProbeFailure.PLAYLIST_OWNER -> "Playlist không thuộc tài khoản đã xác minh. Đã chặn thao tác."
        ProbeFailure.PLAYLIST_PRIVACY -> "Chỉ chấp nhận playlist private, không cộng tác. Không tự đổi quyền riêng tư."
        ProbeFailure.PLAYLIST_NOT_READY -> "Chưa có playlist đã liên kết. Tạo hoặc liên kết playlist trước."
        ProbeFailure.READ_INCOMPLETE -> "Chưa đọc đủ hoặc playlist đang thay đổi. Không thêm bài; hãy đối soát lại."
        ProbeFailure.CREATION_UNCERTAIN -> "Có lần tạo playlist chưa rõ kết quả. Kiểm tra Spotify rồi dán link để liên kết; không tự tạo thêm."
        ProbeFailure.LINK_REQUIRED -> "Đã có playlist thử hoặc cần kiểm tra liên kết. Dán link playlist private của bạn để tiếp tục."
        ProbeFailure.VERIFY_REMOTE -> "Chưa xác nhận được bài trên Spotify. Không báo đã thêm; đối soát lại trước khi gửi thêm."
        ProbeFailure.TRACK_LOCKED -> "Bản thử chỉ theo dõi một bài đã chọn. Tiếp tục dùng đúng bài đó; không có nút đặt lại trạng thái."
        ProbeFailure.CONFIRMATION_REQUIRED -> "Thao tác này cần xác nhận trước khi thực hiện."
        ProbeFailure.JOURNAL -> "Không đọc/ghi an toàn được trạng thái phục hồi. Đã dừng phiên; không tự xóa hay đặt lại dữ liệu."
        ProbeFailure.QUOTA_EXCEEDED -> "Đã hết hạn mức Spotify API. Đã dừng phiên; chờ hạn mức hồi phục trước khi kết nối lại."
        else -> "Không kết nối được. Kiểm tra mạng rồi thử lại."
    }
}
