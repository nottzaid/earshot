package dev.nottzaid.earshot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.nottzaid.earshot.R

@Immutable
data class Palette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,      // violet: the "live" start colour
    val accentWarm: Color,  // coral: the "live" end colour
    val standby: Color,
    val danger: Color,
    val good: Color,
    val caution: Color,
)

private val Dark = Palette(
    dark = true,
    background = Color(0xFF0A0B10),
    surface = Color(0xFF12131B),
    surfaceHigh = Color(0xFF1C1D29),
    outline = Color(0xFF272837),
    text = Color(0xFFF3F2F8),
    textMuted = Color(0xFFA09FB3),
    textFaint = Color(0xFF6A6980),
    accent = Color(0xFF8E7DFF),
    accentWarm = Color(0xFFFF7A66),
    standby = Color(0xFF7D8DB0),
    danger = Color(0xFFFF5468),
    good = Color(0xFF4FD9A0),
    caution = Color(0xFFF5B754),
)

private val Light = Palette(
    dark = false,
    background = Color(0xFFF6F5FA),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDECF4),
    outline = Color(0xFFDFDDEA),
    text = Color(0xFF15141D),
    textMuted = Color(0xFF66657A),
    textFaint = Color(0xFF9C9BAE),
    accent = Color(0xFF6C59F0),
    accentWarm = Color(0xFFF2604B),
    standby = Color(0xFF5C6E96),
    danger = Color(0xFFE23B50),
    good = Color(0xFF17A874),
    caution = Color(0xFFC98A12),
)

val LocalPalette = staticCompositionLocalOf { Dark }

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) =
    Font(R.font.inter, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

private val type = Typography(
    displaySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight(650), fontSize = 34.sp, letterSpacing = (-0.03).em),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.02).em),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = (-0.01).em),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.02.em),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.08.em),
)

@Composable
fun EarshotTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.background, onBackground = p.text,
            surface = p.surface, onSurface = p.text, surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textMuted,
            surfaceContainerLow = p.surface, surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh,
            outline = p.outline, outlineVariant = p.outline, error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.background, onBackground = p.text,
            surface = p.surface, onSurface = p.text, surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textMuted,
            surfaceContainerLow = p.surface, surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh,
            outline = p.outline, outlineVariant = p.outline, error = p.danger,
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
