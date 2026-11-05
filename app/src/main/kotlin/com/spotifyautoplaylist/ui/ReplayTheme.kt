package com.spotifyautoplaylist.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** docs/06-UI-UX.md §2. Dynamic wallpaper colors deliberately remain disabled. */
object ReplayTokens {
    val Background = Color(0xFF090D0B)
    val Surface = Color(0xFF171F1A)
    val Elevated = Color(0xFF223029)
    val Stroke = Color(0xFF34443A)
    val Green = Color(0xFF1ED760)
    val OnGreen = Color(0xFF06140C)
    val Soft = Color(0xFFA6E9B7)
    val Selected = Color(0xFF16472B)
    val Text = Color(0xFFF1FFF5)
    val Secondary = Color(0xFFA9B9AD)
    val Muted = Color(0xFF83958A)
    val Warning = Color(0xFFF6BD60)
    val Error = Color(0xFFFF7B80)
    val PagePadding = 20.dp
    val SectionGap = 24.dp
    val CardPadding = 20.dp
    val SmallRadius = 12.dp
    val CardRadius = 18.dp
    val HeroRadius = 24.dp
    val TouchTarget = 48.dp
}

private val ReplayColors = darkColorScheme(
    primary = ReplayTokens.Green, onPrimary = ReplayTokens.OnGreen,
    primaryContainer = ReplayTokens.Elevated, onPrimaryContainer = ReplayTokens.Soft,
    secondary = ReplayTokens.Soft, onSecondary = ReplayTokens.OnGreen,
    secondaryContainer = ReplayTokens.Selected, onSecondaryContainer = ReplayTokens.Text,
    tertiary = ReplayTokens.Warning, onTertiary = ReplayTokens.OnGreen,
    tertiaryContainer = ReplayTokens.Elevated, onTertiaryContainer = ReplayTokens.Warning,
    background = ReplayTokens.Background, onBackground = ReplayTokens.Text,
    surface = ReplayTokens.Surface, onSurface = ReplayTokens.Text,
    surfaceVariant = ReplayTokens.Elevated, onSurfaceVariant = ReplayTokens.Secondary,
    surfaceContainerLowest = ReplayTokens.Background, surfaceContainerLow = ReplayTokens.Surface,
    surfaceContainer = ReplayTokens.Surface, surfaceContainerHigh = ReplayTokens.Elevated,
    surfaceContainerHighest = ReplayTokens.Elevated,
    surfaceBright = ReplayTokens.Elevated, surfaceDim = ReplayTokens.Background,
    surfaceTint = ReplayTokens.Green,
    inverseSurface = ReplayTokens.Text, inverseOnSurface = ReplayTokens.Background,
    inversePrimary = ReplayTokens.Selected, scrim = Color.Black,
    outline = ReplayTokens.Muted, outlineVariant = ReplayTokens.Stroke,
    error = ReplayTokens.Error, onError = ReplayTokens.OnGreen,
    errorContainer = Color(0xFF482126), onErrorContainer = ReplayTokens.Error,
)

@Composable
fun ReplayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ReplayColors,
        typography = Typography(
            displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp),
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
            headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
            titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        ),
        shapes = Shapes(
            small = RoundedCornerShape(ReplayTokens.SmallRadius),
            medium = RoundedCornerShape(ReplayTokens.CardRadius),
            large = RoundedCornerShape(ReplayTokens.HeroRadius),
        ),
        content = content,
    )
}
