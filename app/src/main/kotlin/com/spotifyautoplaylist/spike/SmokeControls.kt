package com.spotifyautoplaylist.spike

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.spotifyautoplaylist.auth.SmokeOutcome
import com.spotifyautoplaylist.auth.SmokeWriteState

private data class Confirmation(val action: SmokeAction?, val input: String = "")

@Composable
internal fun SmokeControls(state: ProbeUiState, model: AuthProbeViewModel) {
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var openFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Kiểm tra API thật · TASK-004", style = MaterialTheme.typography.titleMedium)
            val playlistUrl = state.smoke?.playlistUrl
            Text("Mỗi thao tác chạy thủ công. Không áp dụng luật đếm lượt hoặc tự đồng bộ.")
            OutlinedButton(onClick = { model.runSmoke(SmokeAction.RECENT) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("1. Đọc Recently Played") }
            state.recent?.let {
                Text("Recently Played HTTP ${it.httpStatus} · nhận ${it.received}, hợp lệ ${it.valid}, lọc ${it.filtered}.")
                Text("Chỉ một trang gần nhất; lịch sử có thể chưa đầy đủ. Không lưu các lượt phát.")
            }
            if (playlistUrl == null) {
                Button(onClick = { confirmation = Confirmation(SmokeAction.CREATE) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("2. Tạo hoặc tìm lại playlist private") }
                OutlinedTextField(value = state.playlistInput, onValueChange = model::editPlaylist,
                    modifier = Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
                    label = { Text("Link playlist private để liên kết") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                OutlinedButton(onClick = { confirmation = Confirmation(SmokeAction.LINK, state.playlistInput) },
                    enabled = !state.busy && state.playlistInput.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Liên kết playlist của tôi")
                }
            } else {
                Text(if (state.smoke?.privateVerified == true) "GET playlist HTTP 200 · private và chủ sở hữu đã xác minh trong phiên này."
                    else "Đã lưu liên kết playlist; cần đối soát lại quyền sở hữu và nội dung.")
                OutlinedButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, playlistUrl.toUri()))
                        openFailed = false
                    } catch (_: ActivityNotFoundException) { openFailed = true }
                    catch (_: SecurityException) { openFailed = true }
                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Mở playlist trong Spotify") }
                if (openFailed) Text("Không mở được Spotify hoặc trình duyệt.", color = MaterialTheme.colorScheme.error)
                Text(state.smoke?.maskedTrackId?.let { "Bài thử đã khóa: $it" } ?: "Chọn một bài thử bằng link lấy từ Spotify.")
                Text(when (state.smoke?.writeState ?: SmokeWriteState.NONE) {
                    SmokeWriteState.NONE -> "Chưa gửi bài thử."
                    SmokeWriteState.VERIFY_REMOTE -> "Cần xác minh trên Spotify; chưa báo đã thêm."
                    SmokeWriteState.PRESENT -> "Đã xác minh bài có trên Spotify trong phiên này."
                    SmokeWriteState.REMOVED_EXTERNALLY -> "Bài đã bị gỡ bên ngoài. App không tự thêm lại."
                })
                OutlinedTextField(value = state.trackInput, onValueChange = model::editTrack,
                    modifier = Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
                    label = { Text("Link bài hát thử trên Spotify") },
                    supportingText = { Text("Một bài duy nhất cho bản thử này. Không phải link album hoặc playlist.") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                Button(onClick = { confirmation = Confirmation(SmokeAction.ADD, state.trackInput) },
                    enabled = !state.busy && state.trackInput.isNotBlank() && state.smoke?.writeState != SmokeWriteState.REMOVED_EXTERNALLY,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("3. Thêm bài thử có xác nhận") }
                OutlinedButton(onClick = { model.runSmoke(SmokeAction.RECONCILE) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("4. Đối soát lại Spotify") }
            }
            state.report?.let {
                Text(when (it.outcome) {
                    SmokeOutcome.PLAYLIST_READY -> "Playlist private và chủ sở hữu khớp tài khoản."
                    SmokeOutcome.PRESENT -> "Đã xác nhận bài có trên Spotify bằng lần đọc lại remote."
                    SmokeOutcome.ALREADY_PRESENT -> "Bài đã có trên Spotify; không gửi POST thêm lần nữa."
                    SmokeOutcome.ABSENT_RETRY_READY -> "Đã đọc đủ, bài hiện chưa có. Chỉ thử gửi lại khi bạn xác nhận; app sẽ đối soát lại trước."
                    SmokeOutcome.REMOVED_EXTERNALLY -> "Bài đã bị gỡ bên ngoài; không thêm lại."
                })
                it.postStatus?.let { code -> Text("POST HTTP $code · đã đọc lại remote để xác minh.") }
                if (it.pages > 0) Text("GET items HTTP 200 · đã đối soát đủ ${it.pages} trang trong lần kiểm tra.")
            }
            Text("Token chỉ ở bộ nhớ; kết thúc phiên để đổi tài khoản. Trạng thái phục hồi riêng của tài khoản được giữ trên máy.")
            OutlinedButton(onClick = { confirmation = Confirmation(null) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Kết thúc phiên kiểm tra") }
        }
    }
    confirmation?.let { pending ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(if (pending.action == null) "Kết thúc phiên?" else "Xác nhận thao tác trên Spotify thật") },
            text = { Text(when (pending.action) {
                SmokeAction.CREATE -> "Tìm lại playlist đã tạo trước. Nếu chưa có, tạo My Replays — AutoPlaylist ở chế độ private. Playlist sẽ còn sau khi gỡ app."
                SmokeAction.LINK -> "Liên kết playlist trong link đã nhập. App chỉ chấp nhận playlist private, không cộng tác và thuộc đúng tài khoản đã xác minh."
                SmokeAction.ADD -> "Thêm một bài trong link đã nhập vào playlist đã liên kết. App đối soát trước và sau khi gửi. Bản thử khóa bài này và không tự xóa hoặc thêm lại bài đã bị gỡ."
                else -> "Bỏ token khỏi phiên hiện tại. Không xóa playlist hay bài hát trên Spotify. Lần sau cần đăng nhập và đối soát lại."
            }) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                if (pending.action == null) model.endSession() else model.runSmoke(pending.action, confirmed = true, input = pending.input)
            }) { Text("Xác nhận") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Hủy") } })
    }
}
