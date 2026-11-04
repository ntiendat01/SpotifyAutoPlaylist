package com.spotifyautoplaylist.ui

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

enum class ReplayGlyph(val data: String) {
    Home("M3 10L12 3L21 10V21H15V14H9V21H3Z"),
    History("M3 12A9 9 0 1 0 6 5M3 3V8H8M12 7V12L16 14"),
    Collection("M3 7L12 2L21 7L12 12ZM3 12L12 17L21 12M3 17L12 22L21 17"),
    Rules("M3 6H8M12 6H21M3 18H14M18 18H21M10 3V9M16 15V21"),
    Settings("M9 3H15L16 6L19 7L22 10V14L19 17L16 18L15 21H9L8 18L5 17L2 14V10L5 7L8 6ZM16 12A4 4 0 1 1 8 12A4 4 0 1 1 16 12"),
    Back("M14 5L7 12L14 19M7 12H21"),
    Arrow("M5 12H19M13 6L19 12L13 18"),
    Check("M4 12L9 17L20 6"),
    Info("M12 10V17M12 6V7M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12"),
    Sync("M3 9A9 9 0 0 1 19 5L21 8M21 3V8H16M21 15A9 9 0 0 1 5 19L3 16M3 21V16H8"),
    Lock("M6 10V7A6 6 0 0 1 18 7V10M4 10H20V22H4ZM12 15V18"),
    Music("M9 18V5L21 3V16M9 18A3 3 0 1 1 3 18A3 3 0 1 1 9 18M21 16A3 3 0 1 1 15 16A3 3 0 1 1 21 16"),
    Pulse("M2 14H6L9 5L13 20L16 10L18 14H22"),
}

/** Original platform vectors; no imitation Spotify mark or font-dependent emoji. */
@Composable
fun ReplayIcon(glyph: ReplayGlyph, modifier: Modifier = Modifier, description: String? = null,
               tint: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary) {
    val vector = remember(glyph) {
        ImageVector.Builder(glyph.name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = PathParser().parsePathString(glyph.data).toNodes(),
                pathFillType = PathFillType.NonZero, fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }
    Icon(vector, description, modifier, tint)
}
