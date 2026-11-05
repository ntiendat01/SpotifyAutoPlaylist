package com.spotifyautoplaylist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/** 050A shell; Navigation Compose owns back stack and per-tab state restoration. */
@Composable
fun ReplayFoundation(
    preview: Boolean,
    playlistName: String? = null,
    onOpenPlaylist: (() -> Unit)? = null,
    onExitPreview: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    version: String = "",
    library: com.spotifyautoplaylist.library.LibraryUiState? = null,
    onRetryLibrary: () -> Unit = {},
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val navigation = rememberNavController()
    val entry by navigation.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: ReplayTab.Home.name
    val detail = route.startsWith("track/")
    val settings = route == "settings" || detail
    val selected = ReplayTab.entries.firstOrNull { it.name == route } ?: ReplayTab.Home
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    fun select(tab: ReplayTab) {
        navigation.navigate(tab.name) {
            popUpTo(navigation.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun back() {
        when {
            settings -> navigation.popBackStack()
            selected != ReplayTab.Home -> select(ReplayTab.Home)
            preview -> onExitPreview()
        }
    }
    ReplayScaffold(
        tab = if (settings) null else selected,
        onSelect = { select(it) },
        onSettings = if (!settings) ({ navigation.navigate("settings") { launchSingleTop = true } }) else null,
        onBack = if (settings || preview) ({ back() }) else null,
    ) { padding ->
        NavHost(navigation, startDestination = ReplayTab.Home.name,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
            ReplayTab.entries.forEach { tab ->
                composable(tab.name) {
                    val openTrack: (String) -> Unit = { id -> navigation.navigate("track/$id") }
                    if (!preview && library != null && tab == ReplayTab.Home) HomeScreen(library, onRetryLibrary,
                        onOpenPlaylist = onOpenPlaylist, onOpenSettings = { navigation.navigate("settings") { launchSingleTop = true } },
                        onOpenTrack = openTrack)
                    else if (!preview && library != null && tab == ReplayTab.History) HistoryScreen(library, onRetryLibrary, openTrack)
                    else FoundationPage(preview, tab, playlistName, onOpenPlaylist, version) { confirmDisconnect = true }
                }
            }
            composable("track/{id}") { e ->
                TrackDetailScreen(library ?: com.spotifyautoplaylist.library.LibraryUiState.NoAccount,
                    e.arguments?.getString("id").orEmpty(), onRetryLibrary, onOpenUrl)
            }
            composable("settings") {
                FoundationPage(preview, null, playlistName, onOpenPlaylist, version) { confirmDisconnect = true }
            }
        }
    }
    BackHandler(enabled = settings || selected != ReplayTab.Home || preview) { back() }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text("Ngắt kết nối Spotify?") },
        text = { Text("Giữ dữ liệu trên máy để kết nối lại cùng tài khoản. Playlist trên Spotify không bị xóa.") },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) { Text("Ngắt kết nối") } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Hủy") } },
    )
}

@Composable
private fun FoundationPage(preview: Boolean, tab: ReplayTab?, playlistName: String?,
                           onOpenPlaylist: (() -> Unit)?, version: String, onDisconnect: () -> Unit) {
    Column(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp).fillMaxWidth()
        .verticalScroll(rememberScrollState()).padding(horizontal = ReplayTokens.PagePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(ReplayTokens.SectionGap)) {
        if (preview) CoverageBanner("Xem trước giao diện · Không kết nối Spotify. Không có dữ liệu tài khoản.")
        else CoverageBanner("Bản kiểm tra giao diện · Chưa bật đồng bộ và tự động thêm bài.")
        when (tab) {
            null -> {
                ReplayHeading("Cài đặt")
                EmptyState("Đồng bộ chưa sẵn sàng", "Thiết lập đồng bộ và thông tin sức khỏe kết nối sẽ có ở bản tiếp theo.", glyph = ReplayGlyph.Settings)
                if (!preview) {
                    Text("Đã kết nối Spotify. Playlist vẫn được giữ trong Spotify khi ngắt kết nối.")
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Ngắt kết nối Spotify") }
                }
                Text("replay. $version", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ReplayTab.Home -> {
                SyncStatusChip("Chưa đồng bộ")
                PlaylistHero(playlistName, confirmedCount = null, onOpen = if (preview) null else onOpenPlaylist)
                ReplayHeading("Sắp đạt ngưỡng")
                EmptyState("Chưa có lượt phát được ghi nhận", "Lịch sử và tiến độ sẽ xuất hiện khi tính năng đồng bộ sẵn sàng.")
                ReplayHeading("Mới vào bộ sưu tập")
                EmptyState("Chưa xác minh bài trên Spotify", "Bài hát chỉ được hiển thị là đã thêm sau khi kiểm tra playlist thật.", glyph = ReplayGlyph.Collection)
            }
            ReplayTab.History -> {
                ReplayHeading("Lịch sử nghe")
                EmptyState("Chưa có lịch sử trên máy", "Bản này chưa nhập lịch sử từ Spotify. Không thể xác định số lượt phát.", glyph = ReplayGlyph.History)
            }
            ReplayTab.Collection -> {
                ReplayHeading("Bộ sưu tập")
                PlaylistHero(playlistName, confirmedCount = null, onOpen = if (preview) null else onOpenPlaylist)
                EmptyState("Chưa kiểm tra nội dung playlist", "Không có số bài đã xác minh để hiển thị.", glyph = ReplayGlyph.Collection)
            }
            ReplayTab.Rules -> {
                ReplayHeading("Quy tắc")
                ReplayCard {
                    Text("3 lượt trong một ngày", style = MaterialTheme.typography.titleLarge)
                    Text("HOẶC", color = MaterialTheme.colorScheme.primary)
                    Text("5 lượt trong 72 giờ", style = MaterialTheme.typography.titleLarge)
                    Text("Quy tắc mặc định · Ngày tính theo giờ Việt Nam. 72 giờ là khoảng trượt.")
                }
                Text("Chỉnh sửa quy tắc và tính số lượt phát sẽ có ở bản tiếp theo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Ứng dụng cá nhân độc lập, không phải ứng dụng chính thức của Spotify.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "050A · Empty foundation", widthDp = 393, heightDp = 850, showBackground = true)
@Preview(name = "050A · Narrow, font 1.3", widthDp = 320, heightDp = 740, fontScale = 1.3f)
@Composable
private fun FoundationPreview() { ReplayTheme { ReplayFoundation(preview = true) } }
