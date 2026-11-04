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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.net.toUri
import com.spotifyautoplaylist.auth.ProbeFailure
import com.spotifyautoplaylist.auth.SpotifyAuth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent account/configuration input and browser-return snapshots from entering captures.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val model: AuthProbeViewModel = viewModel()
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
                AuthProbeScreen(state, model::editClientId, model::start, { model.cancel() })
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
    onConnect: () -> Unit,
    onCancel: () -> Unit,
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
            Text("Bước đầu: kiểm tra đăng nhập Spotify trên điện thoại này.",
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
                modifier = Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
                label = { Text("Client ID") },
                supportingText = { Text("32 ký tự từ Developer App. Không nhập Client Secret.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                isError = state.failure == ProbeFailure.INVALID_CLIENT_ID,
            )
            Button(onClick = onConnect, enabled = !state.busy,
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
                    Text("HTTP ${result.httpStatus} · Đã cấp ${result.scopes.size} quyền yêu cầu.")
                    Text("Callback và trao đổi token thành công. Chưa xác minh tài khoản, Premium hay quyền API playlist.")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Bản thử chỉ kiểm tra OAuth. Chưa đọc lịch sử, chưa tạo hoặc sửa playlist. Token không được lưu; mở lại ứng dụng cần đăng nhập lại.",
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
    ProbePhase.SUCCEEDED -> "Đã hoàn tất thử nghiệm OAuth trên phiên này."
    ProbePhase.FAILED -> when (state.failure) {
        ProbeFailure.INVALID_CLIENT_ID -> "Client ID phải gồm đúng 32 ký tự hex."
        ProbeFailure.PORT_IN_USE -> "Cổng 8765 đang được sử dụng. Đóng phiên đăng nhập khác rồi thử lại."
        ProbeFailure.CALLBACK_TIMEOUT -> "Đã hết thời gian chờ. Hãy thử đăng nhập lại."
        ProbeFailure.CANCELLED -> "Đã hủy phiên đăng nhập."
        ProbeFailure.AUTHORIZATION_DENIED -> "Bạn đã từ chối cấp quyền Spotify. Có thể thử lại."
        ProbeFailure.AUTHORIZATION_FAILED -> "Spotify không chấp nhận đăng nhập. Kiểm tra cấu hình Developer App."
        ProbeFailure.BROWSER_UNAVAILABLE -> "Không mở được trình duyệt. Kiểm tra trình duyệt mặc định rồi thử lại."
        ProbeFailure.TOKEN_HTTP -> "Spotify từ chối trao đổi token (HTTP ${state.httpStatus}). Kiểm tra Client ID và redirect URI trước khi thử lại."
        ProbeFailure.TOKEN_RESPONSE -> "Phản hồi token không đúng cấu trúc mong đợi. Chưa xác nhận thành công."
        ProbeFailure.INSUFFICIENT_SCOPE -> "Spotify chưa cấp đủ ba quyền yêu cầu. Kiểm tra cấu hình Developer App."
        else -> "Không kết nối được. Kiểm tra mạng rồi thử lại."
    }
}
