package com.spotifyautoplaylist.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifyautoplaylist.library.TrackStatus

enum class ReplayTab(val label: String, val glyph: ReplayGlyph) {
    Home("Home", ReplayGlyph.Home), History("Lịch sử", ReplayGlyph.History),
    Collection("Bộ sưu tập", ReplayGlyph.Collection), Rules("Quy tắc", ReplayGlyph.Rules),
}

@Composable
fun ReplayTopBar(onSettings: (() -> Unit)? = null, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) {
            ReplayIcon(ReplayGlyph.Back, description = "Quay lại", tint = MaterialTheme.colorScheme.onSurface)
        }
        Row(Modifier.weight(1f).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("replay", fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-1).sp)
            Text(".", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 32.sp)
        }
        if (onSettings != null) IconButton(onClick = onSettings) {
            ReplayIcon(ReplayGlyph.Settings, description = "Mở cài đặt", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun ReplayBottomBar(selected: ReplayTab, onSelect: (ReplayTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        ReplayTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab, onClick = { onSelect(tab) },
                icon = { ReplayIcon(tab.glyph, Modifier.size(24.dp), tint = if (selected == tab)
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                label = { Text(tab.label, style = MaterialTheme.typography.bodySmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
    }
}

@Composable
fun ReplayScaffold(
    modifier: Modifier = Modifier,
    tab: ReplayTab? = null,
    onSelect: (ReplayTab) -> Unit = {},
    onSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background,
        topBar = { Column(Modifier.statusBarsPadding()) { ReplayTopBar(onSettings, onBack) } },
        bottomBar = { if (tab != null) ReplayBottomBar(tab, onSelect) else footer?.invoke() },
    ) { padding -> content(padding) }
}

@Composable
fun ReplayCard(modifier: Modifier = Modifier, hero: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(if (hero) ReplayTokens.HeroRadius else ReplayTokens.CardRadius),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, ReplayTokens.Stroke)) {
        Column(Modifier.background(Brush.linearGradient(listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (hero) .7f else .25f),
            MaterialTheme.colorScheme.background.copy(alpha = .55f),
        ))).then(if (hero) Modifier.background(heroGlow()) else Modifier)
            .padding(ReplayTokens.CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun PrimaryActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                        enabled: Boolean = true, busy: Boolean = false, glyph: ReplayGlyph? = null) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled && !busy,
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = if (busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .12f),
            disabledContentColor = if (busy) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), shape = CircleShape) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (!busy && glyph != null) ReplayIcon(glyph, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun ReplayHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable
fun SyncStatusChip(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReplayIcon(ReplayGlyph.Sync, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun CoverageBanner(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ReplayTokens.Warning.copy(alpha = .09f))
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ReplayIcon(ReplayGlyph.Info, Modifier.size(24.dp), tint = ReplayTokens.Warning)
        Text(text, color = ReplayTokens.Warning, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(title: String, description: String, modifier: Modifier = Modifier, glyph: ReplayGlyph = ReplayGlyph.Music) {
    Row(modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ReplayIcon(glyph, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun LoadingState(text: String) {
    Row(Modifier.fillMaxWidth().padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

@Composable
fun ErrorState(message: String, retry: (() -> Unit)? = null) {
    ReplayCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(message, color = MaterialTheme.colorScheme.error)
        if (retry != null) OutlinedButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Thử lại") }
    }
}

/** TASK-050I: bounded emerald glow, drawn only inside the hero card (no full-screen gradients). */
@Composable
private fun heroGlow(): Brush = Brush.radialGradient(
    listOf(MaterialTheme.colorScheme.primary.copy(alpha = .14f), androidx.compose.ui.graphics.Color.Transparent),
    center = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f), radius = 900f,
)

/** True when the system disables animations (animator duration scale 0, e.g. power saver / Remove animations). */
val LocalReducedMotion = androidx.compose.runtime.compositionLocalOf<Boolean?> { null }

@Composable
fun reducedMotion(): Boolean = LocalReducedMotion.current ?: run {
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    androidx.compose.runtime.remember(resolver) {
        android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable
fun ThresholdProgress(count: Int, threshold: Int, label: String) {
    require(count >= 0 && threshold > 0)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$count/$threshold $label", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        val target = (count.toFloat() / threshold).coerceIn(0f, 1f)
        // Decorative fill-in only; the exact value is always in the text above and in the semantics.
        val reduced = reducedMotion()
        val anim = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(target) }
        androidx.compose.runtime.LaunchedEffect(target, reduced) {
            if (reduced) anim.snapTo(target) else anim.animateTo(target, androidx.compose.animation.core.tween(200))
        }
        Box(Modifier.fillMaxWidth().clearAndSetSemantics { progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(target, 0f..1f) }) {
            LinearProgressIndicator(progress = { anim.value }, modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

/** Text/state supplied by a caller; never infers Added from a local count. */
@Composable
fun TrackRow(name: String, artists: String, status: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier,
             artworkUrl: String? = null, trackStatus: TrackStatus? = null, emphasized: Boolean = false,
             extra: @Composable ColumnScope.() -> Unit = {}) {
    val colors = CardDefaults.cardColors(containerColor = if (emphasized) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background)
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors) {
        TrackRowBody(name, artists, status, artworkUrl, trackStatus, emphasized, extra)
    } else Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors) {
        TrackRowBody(name, artists, status, artworkUrl, trackStatus, emphasized, extra)
    }
}

@Composable
private fun TrackRowBody(name: String, artists: String, status: String, artworkUrl: String?, trackStatus: TrackStatus?,
                         emphasized: Boolean, extra: @Composable ColumnScope.() -> Unit) {
        Row(Modifier.padding(horizontal = if (emphasized) 16.dp else 0.dp, vertical = 12.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrackArtwork(Modifier.size(56.dp), artworkUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(artists, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (trackStatus != null) TrackStatusBadge(trackStatus)
                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                extra()
            }
        }
}

@Composable
fun TrackStatusBadge(status: TrackStatus) {
    val review = status == TrackStatus.NEEDS_REVIEW || status == TrackStatus.REMOVED_EXTERNALLY
    val color = when {
        review -> ReplayTokens.Warning
        status == TrackStatus.ADDED || status == TrackStatus.NEAR -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = .09f), shape = CircleShape) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReplayIcon(when { review -> ReplayGlyph.Info; status == TrackStatus.ADDED -> ReplayGlyph.Check; else -> ReplayGlyph.Pulse },
                Modifier.size(14.dp), tint = color)
            Text(status.label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
fun PlaylistHero(name: String?, confirmedCount: Int?, onOpen: (() -> Unit)? = null,
                 artworkUrls: List<String> = emptyList(), compact: Boolean = false,
                 footer: @Composable ColumnScope.() -> Unit = {}) {
    ReplayCard(hero = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(if (compact) 64.dp else 80.dp)) {
                val covers = artworkUrls.filter(ArtworkImages::permitted).distinct().take(3)
                if (covers.size <= 1) TrackArtwork(Modifier.fillMaxSize(), covers.firstOrNull())
                else covers.forEachIndexed { index, url ->
                    TrackArtwork(Modifier.size(60.dp).align(Alignment.Center).offset(x = ((index - 1) * 10).dp,
                        y = ((1 - index) * 5).dp), url)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PLAYLIST SPOTIFY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text(name ?: "Playlist của bạn", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
            }
        }
        Text(confirmedCount?.let { "$it bài đã xác minh trên Spotify" } ?: "Chưa xác minh số bài trên Spotify",
            style = if (confirmedCount != null && !compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
            color = if (confirmedCount != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (onOpen != null) PrimaryActionButton("Mở trong Spotify", onOpen, glyph = ReplayGlyph.External)
        footer()
    }
}

/** Decorative, static vector curves: no sampled artwork and no motion to disable. */
@Composable
fun ReplayWave(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val path = Path().apply {
            moveTo(0f, size.height * .2f)
            cubicTo(size.width * .35f, size.height * 1.3f, size.width * .55f, -size.height * .6f, size.width, size.height * .5f)
        }
        drawPath(path, ReplayTokens.Green.copy(alpha = .06f), style = Stroke(28.dp.toPx()))
        drawPath(path, Brush.horizontalGradient(listOf(ReplayTokens.Green.copy(alpha = .1f), ReplayTokens.Green.copy(alpha = .7f), ReplayTokens.Green.copy(alpha = .1f))), style = Stroke(1.dp.toPx()))
    }
}
