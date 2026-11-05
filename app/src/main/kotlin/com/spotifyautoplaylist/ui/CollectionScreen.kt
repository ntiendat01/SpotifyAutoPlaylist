package com.spotifyautoplaylist.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.spotifyautoplaylist.library.*
import com.spotifyautoplaylist.library.LibraryModel.Segment

@Composable
fun CollectionScreen(state: LibraryUiState, onRetry: () -> Unit, onOpenPlaylist: (() -> Unit)?, onOpenTrack: (String) -> Unit) {
    var segment by rememberSaveable { mutableStateOf(Segment.CONFIRMED) }
    LibraryGate(state, onRetry) { s ->
        ReplayHeading("Bộ sưu tập")
        PlaylistHero(s.playlist?.name, s.confirmedCount, onOpen = onOpenPlaylist?.takeIf { s.playlist?.url != null }) {
            Text(s.lastVerifiedAtMs?.let { "Xác minh trên Spotify lần cuối: ${LibraryModel.fullTime(it)}" }
                ?: "Chưa kiểm tra playlist trên Spotify", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.playlist == null) Text("Chưa liên kết playlist.", color = ReplayTokens.Warning)
        }
        val counts = Segment.entries.associateWith { LibraryModel.collection(s, it).size }
        ChoiceChips(Segment.entries, segment, { "${it.label} (${counts[it]})" }) { segment = it }
        val rows = LibraryModel.collection(s, segment)
        if (rows.isEmpty()) when (segment) {
            Segment.CONFIRMED -> EmptyState("Chưa có bài đã xác minh trên Spotify",
                "Bài chỉ hiện ở đây sau khi playlist thật trên Spotify xác nhận đã có bài.", glyph = ReplayGlyph.Collection)
            Segment.PENDING -> EmptyState("Không có bài đang chờ", "Bài đủ điều kiện sẽ chờ ở đây tới khi được xác minh.", glyph = ReplayGlyph.Sync)
            Segment.REVIEW -> EmptyState("Không có bài cần kiểm tra", "Bài bị gỡ khỏi playlist hoặc không thêm được sẽ hiện ở đây.", glyph = ReplayGlyph.Check)
        } else rows.forEach { t ->
            val line = if (segment == Segment.CONFIRMED) t.addedAtMs?.let { "Đã thêm ${LibraryModel.fullTime(it)}" } ?: t.status.label
                else LibraryModel.reason(t, s.nowMs)
            TrackRow(t.name, t.artists, line, { onOpenTrack(t.trackId) })
        }
        if (segment == Segment.REVIEW && rows.isNotEmpty())
            Text("Ứng dụng không bao giờ tự xóa bài khỏi playlist.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
