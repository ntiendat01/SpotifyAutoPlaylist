package com.spotifyautoplaylist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.spotifyautoplaylist.auth.OwnerIdentity
import com.spotifyautoplaylist.auth.SpotifyAuth

/**
 * Shared Client ID + Owner User ID editor for onboarding (first-time) and Settings (account switch).
 * Raw values stay in Compose state only; callers persist via [onSave].
 */
@Composable
fun AuthIdsForm(
    busy: Boolean,
    confirmRequired: Boolean,
    onSave: (clientId: String, ownerUserId: String) -> Unit,
    maskedClientId: String? = null,
    maskedOwnerId: String? = null,
    title: String = "Cấu hình Spotify Developer",
) {
    var clientId by rememberSaveable { mutableStateOf("") }
    var ownerId by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    val clientOk = SpotifyAuth.isValidClientId(clientId.trim())
    val ownerOk = OwnerIdentity.isValid(ownerId.trim())
    val canSave = clientOk && ownerOk && !busy && (!confirmRequired || confirmed)
    ReplayCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (maskedClientId != null && maskedOwnerId != null) {
            Text(
                "Hiện tại: Client ${maskedClientId} · Owner ${maskedOwnerId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Lấy Client ID từ Spotify Developer Dashboard. Owner User ID là giá trị /user/<id> của tài khoản Premium sở hữu app (không phải email).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            clientId,
            { clientId = it },
            label = { Text("Client ID (32 ký tự hex)") },
            singleLine = true,
            enabled = !busy,
            isError = clientId.isNotBlank() && !clientOk,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            ownerId,
            { ownerId = it },
            label = { Text("Owner User ID") },
            singleLine = true,
            enabled = !busy,
            isError = ownerId.isNotBlank() && !ownerOk,
            modifier = Modifier.fillMaxWidth(),
        )
        if (confirmRequired) {
            Text(
                "Đổi ID sẽ xóa lịch sử/playlist liên kết trên máy và yêu cầu đăng nhập lại. Playlist trên Spotify không bị xóa.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(confirmed, enabled = !busy, role = Role.Checkbox) { confirmed = it },
            ) {
                Checkbox(confirmed, null, enabled = !busy)
                Text("Tôi xác nhận đổi Client ID / Owner ID và xóa dữ liệu cục bộ")
            }
        }
        PrimaryActionButton(
            if (confirmRequired) "Lưu và đặt lại tài khoản" else "Lưu cấu hình Spotify",
            { onSave(clientId.trim(), ownerId.trim()) },
            enabled = canSave,
            busy = busy,
            glyph = ReplayGlyph.Settings,
        )
    }
}
