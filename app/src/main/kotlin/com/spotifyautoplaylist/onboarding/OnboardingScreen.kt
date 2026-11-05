package com.spotifyautoplaylist.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spotifyautoplaylist.ui.*

@Composable
fun OnboardingScreen(state: OnboardingUiState, model: OnboardingViewModel, onPreview: () -> Unit) {
    OnboardingContent(state, model::connect, model::cancelLogin, model::editName,
        model::editLink, { model.create(true) }, { model.link(true) }, model::recover, model::disconnect, onPreview)
}

@Composable
internal fun OnboardingContent(
    state: OnboardingUiState,
    onConnect: () -> Unit = {}, onCancel: () -> Unit = {},
    onName: (String) -> Unit = {}, onLinkInput: (String) -> Unit = {},
    onCreate: () -> Unit = {}, onLink: () -> Unit = {}, onRecover: () -> Unit = {},
    onDisconnect: () -> Unit = {}, onPreview: () -> Unit = {},
) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var showPermissions by rememberSaveable { mutableStateOf(false) }
    val setup = state.stage == OnboardingStage.SETUP
    BackHandler(enabled = state.stage == OnboardingStage.WAITING) { onCancel() }
    // Remote operations are bounded, but leaving mid-verification must not suggest cancellation.
    BackHandler(enabled = state.busy && state.stage != OnboardingStage.WAITING) { }
    ReplayScaffold(
        onBack = if (setup && !state.busy) ({ confirm = "disconnect" }) else null,
        footer = if (!setup && state.stage != OnboardingStage.RESTORING) ({
            WelcomeActions(state, onConnect, onCancel, onPreview)
        }) else null,
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()
            .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            state.error?.let { ErrorState(it) }
            when {
                state.stage == OnboardingStage.RESTORING -> {
                    LoadingState("Đang khôi phục kết nối…")
                    Text("Phiên đã lưu được bảo vệ trên điện thoại.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                setup -> {
                    Text("THIẾT LẬP · PLAYLIST SPOTIFY", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text("Tạo không gian\ncho những lần nghe lại.", style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.fillMaxWidth().semantics { heading() })
                    Text("Playlist được lưu trực tiếp trong thư viện Spotify của bạn.", modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SetupSteps()
                    ReplayCard(hero = true) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TrackArtwork(Modifier.size(56.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("PLAYLIST SẼ ĐƯỢC TẠO", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(state.playlistName.ifBlank { DEFAULT_PLAYLIST_NAME }, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        Text("Riêng tư · Không cộng tác", color = MaterialTheme.colorScheme.primary)
                    }
                    OutlinedTextField(state.playlistName, onName, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tên playlist") }, enabled = !state.busy && !state.recoveryRequired,
                        supportingText = { Text("Từ 1 đến 100 ký tự. Bạn có thể đổi tên trong Spotify.") },
                        isError = state.playlistName.trim().length !in 1..100)
                    ReplayCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ReplayIcon(ReplayGlyph.Lock, Modifier.size(24.dp))
                            Text("Quyền riêng tư", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("App chỉ chấp nhận playlist riêng tư, thuộc tài khoản đã xác minh.")
                    }
                    RuleNote()
                    CoverageBanner("Bản kiểm tra này chưa tự động đồng bộ hoặc thêm bài. Playlist có thể được tạo và mở trong Spotify.")
                    if (state.recoveryRequired) {
                        CoverageBanner("Đã ghi nhận yêu cầu tạo trước đó. Xác minh lại để tránh tạo playlist trùng.")
                        PrimaryActionButton("Xác minh lại playlist", onRecover, busy = state.busy)
                    } else PrimaryActionButton("Tạo playlist riêng tư", { confirm = "create" },
                        enabled = state.playlistName.trim().length in 1..100, busy = state.busy)
                    if (state.busy) Text("Đang kiểm tra và lưu thiết lập…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    HorizontalDivider(color = ReplayTokens.Stroke)
                    ReplayHeading("Đã có playlist?")
                    Text("Dùng playlist riêng tư của bạn, kể cả playlist từ lần cài app trước.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(state.playlistLink, onLinkInput, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Link playlist Spotify") }, enabled = !state.busy,
                        supportingText = { Text("App sẽ xác minh chủ sở hữu và quyền riêng tư trước khi liên kết.") })
                    OutlinedButton(onClick = { confirm = "link" }, enabled = !state.busy && state.playlistLink.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Xác minh và liên kết") }
                }
                else -> {
                    Text("Nhạc bạn nghe lại.", textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { heading() })
                    Text("Playlist tự tạo.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineLarge)
                    Text("Những bài bạn nghe nhiều lần,\nở cùng một nơi trên Spotify.", textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReplayMusicIllustration()
                    FeatureCard(ReplayGlyph.History, "Ghi nhận những lần nghe lại", "Đếm lượt phát mà Spotify cung cấp.")
                    FeatureCard(ReplayGlyph.Collection, "Giữ lại bài bạn yêu thích", "Bài đủ ngưỡng được đưa vào playlist thật.")
                    FeatureCard(ReplayGlyph.Sync, "Mở ở bất cứ đâu", "Playlist trong thư viện Spotify của bạn.")
                    RuleNote()
                    Text("Lịch sử lưu trên điện thoại. Đăng nhập trên trang Spotify; ứng dụng không nhận mật khẩu của bạn.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium)
                    if (!state.configured) CoverageBanner("Bản cài này chưa được thiết lập kết nối Spotify. Bạn có thể xem thử giao diện bên dưới.")
                    val waiting = state.stage == OnboardingStage.WAITING
                    if (waiting) {
                        Text("Hoàn tất đăng nhập trong trình duyệt rồi quay lại replay. Nếu đã đóng trình duyệt, chọn Hủy. Phiên chờ tối đa 3 phút.",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { showPermissions = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Quyền truy cập & cách hoạt động") }
                    Text("Cần tài khoản Spotify Premium đã thiết lập cho ứng dụng.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    CoverageBanner("Bản kiểm tra giao diện · Tự động đồng bộ và thêm bài chưa sẵn sàng.")
                }
            }
            ReplayWave()
            Text("Ứng dụng cá nhân độc lập, không phải ứng dụng chính thức của Spotify.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
    if (showPermissions) AlertDialog(
        onDismissRequest = { showPermissions = false }, title = { Text("Quyền truy cập Spotify") },
        text = { Text("replay. xin quyền đọc lượt phát gần đây, đọc playlist riêng tư và tạo/cập nhật playlist riêng tư. Spotify có thể chỉ cung cấp một phần lịch sử. Một lượt phát không có nghĩa là nghe hết bài. Playlist vẫn còn trên Spotify khi gỡ app.") },
        confirmButton = { TextButton(onClick = { showPermissions = false }) { Text("Đã hiểu") } },
    )
    confirm?.let { action ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(when (action) { "create" -> "Tạo playlist trên Spotify?"; "link" -> "Liên kết playlist này?"; else -> "Ngắt kết nối Spotify?" }) },
            text = { Text(when (action) {
                "create" -> "Tạo “${state.playlistName.trim()}” riêng tư trong tài khoản đã xác minh. Nếu đã có playlist từ lần cài trước, chọn Hủy và dán link để liên kết."
                "link" -> "App sẽ kiểm tra playlist thuộc tài khoản đã xác minh và đang riêng tư. Nội dung playlist không bị thay đổi trong bước này."
                else -> "Giữ dữ liệu trên máy để kết nối lại cùng tài khoản. Playlist trên Spotify không bị xóa."
            }) },
            confirmButton = { TextButton(onClick = {
                confirm = null
                when (action) { "create" -> onCreate(); "link" -> onLink(); else -> onDisconnect() }
            }) { Text("Xác nhận") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Hủy") } },
        )
    }
}

/** Keep the first decision and login escape route visible at every scroll/font size. */
@Composable
private fun WelcomeActions(state: OnboardingUiState, onConnect: () -> Unit, onCancel: () -> Unit, onPreview: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Column(Modifier.navigationBarsPadding().imePadding().fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 640.dp).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (state.configured) "Đăng nhập trên Spotify · Lịch sử lưu trên máy"
                else "Bản cài chưa thiết lập kết nối Spotify", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            PrimaryActionButton(when (state.stage) {
                OnboardingStage.WAITING -> "Đang chờ trình duyệt…"
                OnboardingStage.VERIFYING -> "Đang xác minh tài khoản…"
                else -> "Kết nối Spotify"
            }, onConnect, enabled = state.configured, busy = state.busy)
            if (state.stage == OnboardingStage.WAITING) TextButton(onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Hủy đăng nhập") }
            else if (!state.busy) TextButton(onClick = onPreview,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Xem thử giao diện") }
        }
    }
}

@Composable
private fun FeatureCard(glyph: ReplayGlyph, title: String, description: String) {
    ReplayCard {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                ReplayIcon(glyph, Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RuleNote() {
    ReplayCard {
        Text("3 lượt/ngày HOẶC 5 lượt/72 giờ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("Quy tắc mặc định · Lượt phát được Spotify ghi nhận, không đồng nghĩa nghe hết bài.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SetupSteps() {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Đã kết nối", "Playlist", "Sẵn sàng").forEachIndexed { index, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplayIcon(if (index == 0) ReplayGlyph.Check else if (index == 1) ReplayGlyph.Collection else ReplayGlyph.Arrow,
                    Modifier.size(28.dp), tint = if (index <= 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Abstract music cards, not album artwork or a sampled image from the visual reference. */
@Composable
private fun ReplayMusicIllustration() {
    Canvas(Modifier.fillMaxWidth().height(152.dp)) {
        val cardWidth = size.width * .32f
        val cardHeight = size.height * .78f
        listOf(-16f, -5f, 10f).forEachIndexed { index, angle ->
            val left = size.width * (.22f + index * .12f)
            val top = size.height * (.16f - index * .035f)
            rotate(angle, Offset(left + cardWidth / 2, top + cardHeight / 2)) {
                drawRoundRect(Brush.linearGradient(listOf(ReplayTokens.Elevated, ReplayTokens.Background)),
                    Offset(left, top), Size(cardWidth, cardHeight), CornerRadius(12.dp.toPx()))
                drawRoundRect(ReplayTokens.Green.copy(alpha = .45f), Offset(left, top), Size(cardWidth, cardHeight),
                    CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx()))
                drawCircle(ReplayTokens.Green.copy(alpha = .16f), cardWidth * .27f, Offset(left + cardWidth / 2, top + cardHeight * .42f))
                drawCircle(ReplayTokens.Soft, cardWidth * .06f, Offset(left + cardWidth / 2, top + cardHeight * .42f))
                repeat(6) { bar ->
                    val h = cardHeight * (.09f + (bar % 3) * .04f)
                    drawRoundRect(ReplayTokens.Green, Offset(left + cardWidth * (.22f + bar * .095f), top + cardHeight * .84f - h),
                        Size(cardWidth * .04f, h), CornerRadius(2.dp.toPx()))
                }
            }
        }
    }
}

@Preview(name = "050B Welcome", widthDp = 393, heightDp = 850)
@Preview(name = "050B Welcome large type", widthDp = 320, heightDp = 740, fontScale = 1.3f)
@Preview(name = "050B Welcome landscape", widthDp = 850, heightDp = 393)
@Composable
private fun WelcomePreview() { ReplayTheme { OnboardingContent(OnboardingUiState(stage = OnboardingStage.WELCOME)) } }

@Preview(name = "050B Playlist setup", widthDp = 393, heightDp = 850)
@Composable
private fun SetupPreview() { ReplayTheme { OnboardingContent(OnboardingUiState(stage = OnboardingStage.SETUP)) } }
