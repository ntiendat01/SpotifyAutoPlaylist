package com.spotifyautoplaylist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spotifyautoplaylist.data.settings.AccountSettings
import com.spotifyautoplaylist.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Saves thresholds and re-evaluates stored events; returns how many tracks newly qualified. Throws on failure. */
typealias SaveRules = suspend (daily: Int, rolling: Int) -> Int

@Composable
private fun Stepper(title: String, unit: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    ReplayCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onChange(value - 1) }, enabled = value > range.first,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Giảm $title" }) { Text("−") }
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "$title: $value $unit"; liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$value", style = MaterialTheme.typography.displaySmall)
                Text(unit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            OutlinedButton(onClick = { onChange(value + 1) }, enabled = value < range.last,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Tăng $title" }) { Text("+") }
        }
        Text("Từ ${range.first} đến ${range.last}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RulesScreen(state: LibraryUiState, onRetry: () -> Unit, onSave: SaveRules?) {
    LibraryGate(state, onRetry) { s ->
        var daily by rememberSaveable(s.dailyThreshold) { mutableStateOf(s.dailyThreshold) }
        var rolling by rememberSaveable(s.rollingThreshold) { mutableStateOf(s.rollingThreshold) }
        var busy by remember { mutableStateOf(false) }
        var message by rememberSaveable { mutableStateOf<String?>(null) }
        var error by rememberSaveable { mutableStateOf<String?>(null) }
        var confirmDiscard by rememberSaveable { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val dirty = daily != s.dailyThreshold || rolling != s.rollingThreshold
        val valid = daily in AccountSettings.DAILY_RANGE && rolling in AccountSettings.ROLLING_RANGE
        BackHandler(enabled = dirty && !busy) { confirmDiscard = true }

        ReplayHeading("Quy tắc")
        Text("Chỉ cần đạt một trong hai điều kiện.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Stepper("Ngưỡng theo ngày", "lượt trong một ngày", daily, AccountSettings.DAILY_RANGE) { daily = it; message = null }
        Text("HOẶC", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
        Stepper("Ngưỡng 72 giờ", "lượt trong 72 giờ", rolling, AccountSettings.ROLLING_RANGE) { rolling = it; message = null }
        Text("Ngày theo giờ Việt Nam · 72 giờ là khoảng trượt; chỉ tính lượt nghe từ 30 giây trở lên (ước lượng).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ReplayHeading("Xem trước")
        val preview = remember(s, daily, rolling) { LibraryModel.rulesPreview(s, daily, rolling) }
        ReplayCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(if (dirty) "${preview.newlyQualified.size} bài sẽ đủ điều kiện thêm với quy tắc này"
                else "Quy tắc hiện tại · ${preview.alreadyQualified} bài đã đủ điều kiện")
            preview.newlyQualified.take(3).forEach { Text("• ${it.name} — ${it.artists}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (preview.newlyQualified.size > 3) Text("và ${preview.newlyQualified.size - 3} bài khác", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Tính từ lượt phát đã lưu trên máy. Tăng ngưỡng không xóa bài đã đủ điều kiện hoặc đã thêm.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.coverage != Coverage.COMPLETE_SINCE_BASELINE) CoverageNotice(s.coverage)

        PrimaryActionButton("Lưu quy tắc", {
            val save = onSave ?: return@PrimaryActionButton
            busy = true; error = null
            scope.launch {
                try {
                    val added = save(daily, rolling)
                    message = "Đã lưu. Bài trong playlist không bị xóa." +
                        if (added > 0) " $added bài mới đủ điều kiện sẽ được thêm ở lần đồng bộ tiếp theo." else ""
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { error = "Không lưu được quy tắc. Thay đổi của bạn vẫn còn, hãy thử lại." }
                finally { busy = false }
            }
        }, enabled = dirty && valid && onSave != null && !busy, busy = busy)
        if (dirty) OutlinedButton(onClick = { daily = s.dailyThreshold; rolling = s.rollingThreshold }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Bỏ thay đổi") }
        TextButton(onClick = { daily = 3; rolling = 5; message = null }, enabled = !busy && (daily != 3 || rolling != 5),
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Khôi phục mặc định (3/ngày, 5/72 giờ)") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        error?.let { ErrorState(it) }

        if (confirmDiscard) AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Bỏ thay đổi?") },
            text = { Text("Quy tắc chưa được lưu sẽ trở về giá trị hiện tại.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; daily = s.dailyThreshold; rolling = s.rollingThreshold }) { Text("Bỏ thay đổi") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Tiếp tục sửa") } },
        )
    }
}
