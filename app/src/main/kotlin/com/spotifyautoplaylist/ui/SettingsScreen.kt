package com.spotifyautoplaylist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.spotifyautoplaylist.library.*
import com.spotifyautoplaylist.playlist.RelinkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Bound to [com.spotifyautoplaylist.sync.SyncRepository] in the app; fakes in tests. */
interface SettingsActions {
    /** False when nobody is signed in (nothing enqueued). */
    suspend fun syncNow(): Boolean
    suspend fun relink(input: String, confirmed: Boolean): RelinkResult
}

/** Optional TASK-061 runtime Developer App ID editor; omit in preview / unit tests that do not cover it. */
interface AuthIdSettingsActions {
    val maskedClientId: String
    val maskedOwnerId: String
    suspend fun save(clientId: String, ownerUserId: String, confirmed: Boolean)
}

object SettingsCopy {
    fun relink(r: RelinkResult): String = when (r) {
        is RelinkResult.Relinked -> "Đã liên kết playlist mới. ${r.moved} bài đang chờ sẽ được thêm ở lần đồng bộ tới (đã yêu cầu đồng bộ)."
        is RelinkResult.Rejected -> when (r.reason) {
            RelinkResult.Reason.CONFIRMATION -> "Hãy xác nhận trước khi liên kết lại."
            RelinkResult.Reason.INVALID_LINK -> "Liên kết không phải playlist Spotify hợp lệ."
            RelinkResult.Reason.NOT_LINKED -> "Chưa có playlist nào được liên kết."
            RelinkResult.Reason.NOT_REQUIRED -> "Playlist hiện tại vẫn hoạt động; không cần liên kết lại."
            RelinkResult.Reason.UNAVAILABLE -> "Không kiểm tra được Spotify lúc này. Thử lại sau."
            RelinkResult.Reason.SAME_PLAYLIST -> "Đây là playlist đang được liên kết."
            RelinkResult.Reason.OWNER -> "Playlist phải thuộc tài khoản Spotify của bạn."
            RelinkResult.Reason.PRIVACY -> "Playlist phải ở chế độ riêng tư và không cộng tác."
            RelinkResult.Reason.RESPONSE -> "Spotify trả về dữ liệu không đầy đủ. Thử lại sau."
            RelinkResult.Reason.NOT_FOUND -> "Không tìm thấy playlist này."
            RelinkResult.Reason.ACCOUNT_CHANGED -> "Tài khoản Spotify đã thay đổi. Hãy mở lại ứng dụng."
        }
    }
}

@Composable
fun SettingsScreen(state: LibraryUiState, onRetry: () -> Unit, actions: SettingsActions?, version: String,
                   onOpenPlaylist: (() -> Unit)?, authIds: AuthIdSettingsActions? = null,
                   onDisconnect: (() -> Unit)? = null) {
    LibraryGate(state, onRetry) { s ->
        val scope = rememberCoroutineScope()
        val st = s.status
        ReplayHeading("Cài đặt")
        ReplayCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReplayIcon(ReplayGlyph.Collection, Modifier.size(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.playlist?.name ?: "Playlist Spotify", style = MaterialTheme.typography.titleMedium)
                    Text(if (onDisconnect != null) "Đã kết nối Spotify" else "Chưa kết nối Spotify",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onOpenPlaylist != null) OutlinedButton(onClick = onOpenPlaylist, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Mở playlist trong Spotify", Modifier.weight(1f))
                ReplayIcon(ReplayGlyph.External, Modifier.size(20.dp))
            }
        }
        ReplayHeading("Sức khỏe đồng bộ")
        ReplayCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(SyncCopy.headline(st, s.nowMs), style = MaterialTheme.typography.titleMedium,
                color = if (SyncCopy.isProblem(st)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (st.outcome != "SUCCESS") Text("Lần thành công gần nhất: " + (st.lastSuccessAtMs?.let { LibraryModel.time(it, s.nowMs) } ?: "chưa có"),
                style = MaterialTheme.typography.bodyMedium)
            st.blockedUntilMs?.takeIf { it > s.nowMs }?.let { Text("Tạm dừng đến ${LibraryModel.time(it, s.nowMs)}") }
            if (st.outcome != null) Text("Lần chạy gần nhất: nhập ${st.imported} lượt phát · ${st.newlyQualified} bài mới đủ điều kiện",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val q = Diagnostics.queue(s)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Đã thêm (đã xác minh) ${q[TrackStatus.ADDED]} · Đang chờ ${q[TrackStatus.ADDING]}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Text("Đang xác minh ${q[TrackStatus.VERIFYING]} · Cần kiểm tra ${q[TrackStatus.NEEDS_REVIEW]}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.coverage != Coverage.COMPLETE_SINCE_BASELINE) CoverageNotice(s.coverage)

        // Manual sync: requestManualSync only enqueues; the result arrives through the status flow.
        var requestedAt by rememberSaveable { mutableStateOf<Long?>(null) }
        var requesting by remember { mutableStateOf(false) }
        var syncError by rememberSaveable { mutableStateOf<String?>(null) }
        PrimaryActionButton("Đồng bộ ngay", {
            val a = actions ?: return@PrimaryActionButton
            requesting = true; syncError = null
            val at = System.currentTimeMillis()
            scope.launch {
                try { if (a.syncNow()) requestedAt = at else syncError = "Chưa kết nối Spotify nên không thể đồng bộ." }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { syncError = "Không yêu cầu đồng bộ được. Thử lại." }
                finally { requesting = false }
            }
        }, enabled = actions != null && !requesting, busy = requesting, glyph = ReplayGlyph.Sync)
        if (actions == null) Text("Bản dựng này chưa cấu hình Spotify nên không thể đồng bộ.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        requestedAt?.let { at ->
            val done = (st.finishedAtMs ?: 0) >= at
            Text(if (done) "Kết quả đồng bộ thủ công: " + when {
                    st.outcome == "SUCCESS" && st.newlyQualified == 0 -> "Đồng bộ thành công — không có bài mới"
                    st.outcome == "SUCCESS" -> "Đồng bộ thành công — ${st.newlyQualified} bài mới đủ điều kiện (xem Bộ sưu tập để biết bài đã xác minh)"
                    else -> SyncCopy.headline(st, s.nowMs)
                }
                 else "Đã yêu cầu đồng bộ. Đang chờ kết quả…",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (done && SyncCopy.isProblem(st)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        syncError?.let { ErrorState(it) }

        if (Diagnostics.relinkRequired(st) && actions != null) RelinkCard(actions)

        ReplayHeading("Chẩn đoán")
        val diag = remember(s, version) { Diagnostics.text(s, version) }
        val clipboard = LocalClipboardManager.current
        var expanded by rememberSaveable { mutableStateOf(false) }
        ReplayCard {
            Text("Thông tin kỹ thuật để kiểm tra kết nối và đồng bộ.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (expanded) "Đã mở rộng" else "Đã thu gọn" }) {
                Text(if (expanded) "Ẩn chi tiết chẩn đoán" else "Xem chi tiết chẩn đoán")
            }
            if (expanded) SelectionContainer { Text(diag, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(diag)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sao chép chẩn đoán") }
        }

        if (authIds != null) {
            ReplayHeading("Developer App")
            var idBusy by remember { mutableStateOf(false) }
            var idError by rememberSaveable { mutableStateOf<String?>(null) }
            var idOk by rememberSaveable { mutableStateOf<String?>(null) }
            AuthIdsForm(
                busy = idBusy,
                confirmRequired = true,
                maskedClientId = authIds.maskedClientId,
                maskedOwnerId = authIds.maskedOwnerId,
                onSave = { client, owner ->
                    idBusy = true; idError = null; idOk = null
                    scope.launch {
                        try {
                            authIds.save(client, owner, confirmed = true)
                            idOk = "Đã lưu ID mới. Dữ liệu cục bộ đã xóa — hãy đăng nhập lại bằng tài khoản Premium tương ứng."
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            idError = when (e) {
                                is com.spotifyautoplaylist.auth.AuthException ->
                                    com.spotifyautoplaylist.onboarding.authMessage(e)
                                else -> "Không lưu được cấu hình. Kiểm tra Client ID / Owner ID rồi thử lại."
                            }
                        } finally { idBusy = false }
                    }
                },
            )
            idOk?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            idError?.let { ErrorState(it) }
        }

        ReplayHeading("Tài khoản")
        if (onDisconnect != null) {
            Text("Đã kết nối Spotify. Playlist vẫn được giữ trong Spotify khi ngắt kết nối.")
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Ngắt kết nối Spotify") }
        }
        Text("replay. $version", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RelinkCard(actions: SettingsActions) {
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    ReplayCard {
        Text("Liên kết lại playlist", style = MaterialTheme.typography.titleMedium)
        Text("Playlist cũ không còn dùng được. Dán liên kết một playlist riêng tư của bạn để tiếp tục thêm bài. Ứng dụng không tạo playlist mới.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(input, { input = it; result = null }, label = { Text("Liên kết playlist Spotify") },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(confirmed, enabled = !busy, role = Role.Checkbox) { confirmed = it }) {
            Checkbox(confirmed, null, enabled = !busy)
            Text("Tôi xác nhận dùng playlist này thay cho playlist cũ")
        }
        PrimaryActionButton("Liên kết lại", {
            busy = true
            scope.launch {
                result = try { SettingsCopy.relink(actions.relink(input.trim(), confirmed)) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { "Không liên kết lại được. Thử lại sau." }
                busy = false
            }
        }, enabled = input.isNotBlank() && confirmed && !busy, busy = busy)
        result?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}
