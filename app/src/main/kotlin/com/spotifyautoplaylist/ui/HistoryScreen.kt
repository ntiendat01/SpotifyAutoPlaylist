package com.spotifyautoplaylist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.spotifyautoplaylist.data.local.QualifiedBy
import com.spotifyautoplaylist.library.*
import com.spotifyautoplaylist.library.LibraryModel.StatusFilter
import com.spotifyautoplaylist.library.LibraryModel.TimeFilter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) },
                modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@Composable
fun HistoryScreen(state: LibraryUiState, onRetry: () -> Unit, onOpenTrack: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableStateOf(TimeFilter.ALL) }
    var status by rememberSaveable { mutableStateOf(StatusFilter.ALL) }
    LibraryGate(state, onRetry) { s ->
        ReplayHeading("Lịch sử nghe")
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Tìm bài hát hoặc nghệ sĩ") },
            leadingIcon = { ReplayIcon(ReplayGlyph.History, Modifier.size(20.dp)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        ChoiceChips(TimeFilter.entries, time, { it.label }) { time = it }
        ChoiceChips(StatusFilter.entries, status, { it.label }) { status = it }
        CoverageNotice(s.coverage)
        val rows = LibraryModel.history(s, query, time, status)
        Text("${rows.size} bài · ${LibraryModel.plays(rows, time)} lượt phát được ghi nhận",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            s.tracks.isEmpty() -> EmptyState("Chưa có lịch sử trên máy", "Lượt phát xuất hiện sau lần đồng bộ thành công đầu tiên.", glyph = ReplayGlyph.History)
            rows.isEmpty() -> EmptyState("Không có bài phù hợp", "Thử đổi từ khóa hoặc bộ lọc.", glyph = ReplayGlyph.History)
            else -> rows.forEach { t ->
                TrackRow(t.name, t.artists, "Hôm nay ${t.todayCount} · 72 giờ ${t.rolling72hCount} · ${t.status.label}",
                    { onOpenTrack(t.trackId) })
            }
        }
    }
}

@Composable
fun TrackDetailScreen(state: LibraryUiState, trackId: String, onRetry: () -> Unit, onOpenUrl: ((String) -> Unit)?) {
    LibraryGate(state, onRetry) { s ->
        val t = s.tracks.firstOrNull { it.trackId == trackId }
        if (t == null) { EmptyState("Không tìm thấy bài trên máy", "Bài này không còn trong dữ liệu của tài khoản hiện tại."); return@LibraryGate }
        ReplayCard(hero = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TrackArtwork(Modifier.size(72.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(t.artists, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    t.album?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text(t.status.label, color = MaterialTheme.colorScheme.primary)
            ThresholdProgress(t.todayCount, s.dailyThreshold, "hôm nay (giờ Việt Nam)")
            ThresholdProgress(t.rolling72hCount, s.rollingThreshold, "trong 72 giờ gần nhất")
            t.qualifiedBy?.let { by ->
                Text(when (by) {
                    QualifiedBy.DAILY -> "Đủ điều kiện theo quy tắc lượt trong ngày"
                    QualifiedBy.ROLLING -> "Đủ điều kiện theo quy tắc 72 giờ"
                    QualifiedBy.BOTH -> "Đủ điều kiện theo cả hai quy tắc"
                } + (t.qualifiedAtMs?.let { " · ${LibraryModel.fullTime(it)}" } ?: ""))
            }
            t.addedAtMs?.let { Text("Đã thêm vào playlist trên Spotify: ${LibraryModel.fullTime(it)}") }
            if (t.spotifyUrl != null && onOpenUrl != null) PrimaryActionButton("Mở bài trong Spotify", { onOpenUrl(t.spotifyUrl) })
        }
        CoverageBanner("Lượt phát Spotify ghi nhận không đồng nghĩa nghe hết bài")
        ReplayHeading("Các lượt phát (${t.playTimesMs.size})")
        val shown = t.playTimesMs.take(MAX_EVENTS)
        if (shown.isEmpty()) Text("Chưa có lượt phát được ghi nhận.")
        shown.forEach { Text(LibraryModel.fullTime(it)) }
        if (t.playTimesMs.size > MAX_EVENTS) Text("và ${t.playTimesMs.size - MAX_EVENTS} lượt cũ hơn", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Giờ Việt Nam (UTC+7), theo thời điểm Spotify ghi nhận.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val MAX_EVENTS = 50
