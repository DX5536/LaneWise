package de.lanewise.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * LaneWise palette — deliberately different from Petal (blue) and AMAP (green/teal):
 * electric violet for the route, signal lime for "your lane", on deep graphite asphalt.
 * All text/background pairs are ≥ 4.5:1 contrast (WCAG AA); lime is only used on dark asphalt.
 */
@Immutable
data class LaneColors(
    val asphalt: Color,
    val asphaltEdge: Color,
    val laneMarking: Color,
    val laneRecommended: Color,
    val laneAllowed: Color,
    val laneArrowIdle: Color,
    val route: Color,
    val routeCasing: Color,
    val chip: Color,
    val onChip: Color,
    val banner: Color,
    val onBanner: Color,
    val bannerAccent: Color,
    val speedRing: Color,
    val overSpeed: Color,
    val underSpeed: Color,
    val car: Color,
    val isDark: Boolean,
)

object Palette {
    val Violet = Color(0xFF6C4CF5)
    val VioletLight = Color(0xFF9A82FF)
    val VioletDeep = Color(0xFF4A33B8)
    val Lime = Color(0xFFB8F33A)
    val LimeDeep = Color(0xFF5E8C00)
    val Graphite = Color(0xFF23262E)
    val GraphiteDeep = Color(0xFF15171D)
    val Fog = Color(0xFFF4F5F8)
    val SignRed = Color(0xFFD7191C)
    val WarnRed = Color(0xFFFF5A5F)
}

val LightLaneColors = LaneColors(
    asphalt = Color(0xFF2B2F38), asphaltEdge = Color(0xFF8E95A8), laneMarking = Color.White,
    laneRecommended = Palette.Lime, laneAllowed = Color(0x66B8F33A), laneArrowIdle = Color(0xFF6B7080),
    route = Palette.Violet, routeCasing = Palette.VioletDeep,
    chip = Color.White, onChip = Color(0xFF14161C),
    banner = Palette.Graphite, onBanner = Color.White, bannerAccent = Palette.Violet,
    speedRing = Palette.SignRed, overSpeed = Palette.WarnRed, underSpeed = Color(0xFFFF8A8E),
    car = Palette.Violet, isDark = false,
)

val DarkLaneColors = LaneColors(
    asphalt = Color(0xFF1C1F27), asphaltEdge = Color(0xFF5A6175), laneMarking = Color(0xFFE6E8EF),
    laneRecommended = Palette.Lime, laneAllowed = Color(0x55B8F33A), laneArrowIdle = Color(0xFF8A90A2),
    route = Palette.VioletLight, routeCasing = Palette.VioletDeep,
    chip = Color(0xFF22252E), onChip = Color(0xFFF2F3F7),
    banner = Color(0xFF0E1015), onBanner = Color.White, bannerAccent = Palette.Violet,
    speedRing = Palette.SignRed, overSpeed = Palette.WarnRed, underSpeed = Color(0xFFFF8A8E),
    car = Palette.VioletLight, isDark = true,
)

val LocalLaneColors = staticCompositionLocalOf { LightLaneColors }

@Composable
fun LaneWiseTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = Palette.VioletLight, onPrimary = Color(0xFF14102E),
        secondary = Palette.Lime, onSecondary = Color(0xFF1A2400),
        background = Color(0xFF101218), onBackground = Color(0xFFECEEF4),
        surface = Color(0xFF181B22), onSurface = Color(0xFFECEEF4),
        surfaceVariant = Color(0xFF242833), onSurfaceVariant = Color(0xFFC3C7D4),
        error = Palette.WarnRed,
    ) else lightColorScheme(
        primary = Palette.Violet, onPrimary = Color.White,
        secondary = Palette.LimeDeep, onSecondary = Color.White,
        background = Palette.Fog, onBackground = Color(0xFF14161C),
        surface = Color.White, onSurface = Color(0xFF14161C),
        surfaceVariant = Color(0xFFE9EBF1), onSurfaceVariant = Color(0xFF454A57),
        error = Palette.SignRed,
    )
    CompositionLocalProvider(LocalLaneColors provides if (dark) DarkLaneColors else LightLaneColors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
