package com.spotifyautoplaylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spotifyautoplaylist.library.*

/** Shared page column: scrolls, wraps at large font, bounded width on tablets/landscape. */
@Composable
fun ReplayPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp).fillMaxWidth()
        .verticalScroll(rememberScrollState()).padding(horizontal = ReplayTokens.PagePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(ReplayTokens.SectionGap), content = content)
}

/** Loading / no account / error wrapper shared by the data-backed tabs. */
@Composable
fun LibraryGate(state: LibraryUiState, onRetry: () -> Unit, content: @Composable ColumnScope.(LibrarySnapshot) -> Unit) {
    ReplayPage {
        when (state) {
            LibraryUiState.Loading -> LoadingState("Đang đọc dữ liệu trên máy…")
            LibraryUiState.NoAccount -> EmptyState("Chưa kết nối Spotify", "Kết nối lại để xem dữ liệu của tài khoản này.", glyph = ReplayGlyph.Settings)
            is LibraryUiState.Error -> ErrorState(state.message, onRetry)
            is LibraryUiState.Ready -> content(state.snapshot)
        }
        Text("Ứng dụng cá nhân độc lập, không phải ứng dụng chính thức của Spotify.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Status strip: sync truth plus the coverage caveat; tapping opens Sync Health (Settings). */
@Composable
fun SyncStrip(s: LibrarySnapshot, onOpenSettings: (() -> Unit)?) {
    val text = SyncCopy.headline(s.status, s.nowMs)
    val problem = SyncCopy.isProblem(s.status)
    Surface(shape = RoundedCornerShape(ReplayTokens.SmallRadius),
        color = if (problem) ReplayTokens.Warning.copy(alpha = .09f) else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().then(if (onOpenSettings != null)
            Modifier.clickable(role = Role.Button, onClickLabel = "Mở tình trạng đồng bộ") { onOpenSettings() } else Modifier)) {
        Row(Modifier.padding(16.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReplayIcon(if (problem) ReplayGlyph.Info else ReplayGlyph.Sync, Modifier.size(24.dp),
                tint = if (problem) ReplayTokens.Warning else MaterialTheme.colorScheme.primary)
            Text(text, color = if (problem) ReplayTokens.Warning else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun CoverageNotice(coverage: Coverage) {
    when (coverage) {
        Coverage.PARTIAL -> CoverageBanner("Lịch sử có thể chưa đầy đủ. Số lượt chỉ gồm lượt phát Spotify trả về cho ứng dụng.")
        Coverage.UNKNOWN -> CoverageBanner("Độ đầy đủ lịch sử: chưa xác định. Số lượt là lượt phát được ghi nhận trên máy.")
        Coverage.COMPLETE_SINCE_BASELINE -> Unit
    }
}

@Composable
fun HomeScreen(state: LibraryUiState, onRetry: () -> Unit, onOpenPlaylist: (() -> Unit)?,
               onOpenSettings: (() -> Unit)?, onOpenTrack: ((String) -> Unit)?) {
    LibraryGate(state, onRetry) { s ->
        SyncStrip(s, onOpenSettings)
        CoverageNotice(s.coverage)
        PlaylistHero(s.playlist?.name, s.confirmedCount, onOpen = onOpenPlaylist?.takeIf { s.playlist?.url != null }) {
            s.lastVerifiedAtMs?.let { Text("Kiểm tra playlist trên Spotify lần cuối: ${LibraryModel.time(it, s.nowMs)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (s.playlist == null) Text("Chưa liên kết playlist. Thiết lập playlist để bắt đầu thêm bài.", color = ReplayTokens.Warning)
            else if (!s.playlist.verified) Text("Playlist chưa được xác minh quyền sở hữu.", color = ReplayTokens.Warning)
        }

        ReplayHeading("Sắp đạt ngưỡng")
        val near = LibraryModel.almostThere(s)
        if (near.isEmpty()) EmptyState("Chưa có bài nào gần đạt ngưỡng",
            if (s.tracks.isEmpty()) "Bài có lượt phát gần đây sẽ xuất hiện ở đây." else "Bài nghe trong 72 giờ qua sẽ xuất hiện ở đây.")
        else near.forEach { t ->
            TrackRow(t.name, t.artists, t.status.label, onOpenTrack?.let { { it(t.trackId) } }) {
                ThresholdProgress(t.todayCount, s.dailyThreshold, "hôm nay")
                ThresholdProgress(t.rolling72hCount, s.rollingThreshold, "trong 72 giờ")
            }
        }

        ReplayHeading("Mới vào bộ sưu tập")
        val added = LibraryModel.newlyCollected(s)
        if (added.isEmpty()) EmptyState("Chưa có bài đã xác minh trên Spotify",
            "Bài chỉ hiện ở đây sau khi playlist thật trên Spotify xác nhận đã có bài.", glyph = ReplayGlyph.Collection)
        else added.forEach { t ->
            TrackRow(t.name, t.artists, t.addedAtMs?.let { "Đã thêm ${LibraryModel.date(it)}" } ?: TrackStatus.ADDED.label,
                onOpenTrack?.let { { it(t.trackId) } })
        }

        ReplayHeading("Nhịp nghe 7 ngày")
        ListeningPulse(s)
    }
}

/** Simple accessible bar chart of locally recorded plays per Vietnam day (06 §4.6). */
@Composable
fun ListeningPulse(s: LibrarySnapshot) {
    val total = s.days.sumOf { it.count }
    if (total == 0) {
        EmptyState("Chưa có lượt phát được ghi nhận.", "Biểu đồ hiển thị khi có lượt phát trong 7 ngày gần nhất.", glyph = ReplayGlyph.History)
        return
    }
    val max = s.days.maxOf { it.count }.coerceAtLeast(1)
    ReplayCard {
        Text("$total lượt phát được Spotify ghi nhận", style = MaterialTheme.typography.titleMedium)
        if (s.coverage != Coverage.COMPLETE_SINCE_BASELINE) Text("Một phần · có thể thiếu lượt phát", color = ReplayTokens.Warning,
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            s.days.forEach { d ->
                Column(Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics {
                    contentDescription = "${d.label} ${LibraryModel.date(d.day.atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toEpochMilli())}: ${d.count} lượt"
                }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text("${d.count}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.fillMaxWidth().fillMaxHeight(.7f * d.count / max + .02f).clip(RoundedCornerShape(6.dp))
                        .background(if (d.count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                    Text(d.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Lượt phát, không phải số phút nghe. Ngày theo giờ Việt Nam.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { })
    }
}
