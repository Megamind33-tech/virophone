package com.viroreach.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// Every role Material reads is given a Viro colour. Left unset, Material
// falls back to its own baseline — a purple-grey — and that grey is what every
// default card, chip, dialog, menu, sheet and switch track was quietly drawn
// in, on screens that otherwise looked like Viro.
private val ViroLightColorScheme = lightColorScheme(
    primary = ViroColors.BluePrimary,
    onPrimary = ViroColors.SurfaceLight,
    primaryContainer = ViroLightPalette.surfaceRaised,
    onPrimaryContainer = ViroColors.TextPrimaryLight,
    secondary = ViroColors.BlueAccent,
    onSecondary = ViroColors.SurfaceLight,
    // A chosen chip is the accent, as the Contacts filter chips are.
    secondaryContainer = ViroColors.BluePrimary,
    onSecondaryContainer = ViroColors.SurfaceLight,
    tertiary = ViroColors.BlueAccent,
    onTertiary = ViroColors.SurfaceLight,
    background = ViroColors.WarmBackgroundLight,
    onBackground = ViroColors.TextPrimaryLight,
    surface = ViroColors.SurfaceLight,
    onSurface = ViroColors.TextPrimaryLight,
    surfaceVariant = ViroLightPalette.surfaceRaised,
    onSurfaceVariant = ViroColors.TextSecondaryLight,
    surfaceTint = Color.Transparent,
    inverseSurface = ViroColors.TextPrimaryLight,
    inverseOnSurface = ViroColors.SurfaceLight,
    inversePrimary = ViroColors.BlueAccent,
    outline = ViroColors.TextSecondaryLight,
    outlineVariant = ViroLightPalette.divider,
    error = ViroColors.Error,
    surfaceBright = ViroColors.SurfaceLight,
    surfaceDim = ViroColors.WarmBackgroundLight,
    surfaceContainerLowest = ViroColors.SurfaceLight,
    surfaceContainerLow = ViroColors.WarmBackgroundLight,
    surfaceContainer = ViroColors.SurfaceLight,
    surfaceContainerHigh = ViroColors.SurfaceLight,
    surfaceContainerHighest = ViroColors.SurfaceLight,
)

private val ViroDarkColorScheme = darkColorScheme(
    primary = ViroColors.ElectricBlue,
    onPrimary = ViroColors.TextPrimaryDark,
    primaryContainer = ViroColors.NavySurfaceElevated,
    onPrimaryContainer = ViroColors.TextPrimaryDark,
    secondary = ViroColors.BlueAccent,
    onSecondary = ViroColors.NavyBackground,
    // A chosen chip is the accent, as the Contacts filter chips are.
    secondaryContainer = ViroColors.ElectricBlue,
    onSecondaryContainer = ViroColors.TextPrimaryDark,
    tertiary = ViroColors.BlueAccent,
    onTertiary = ViroColors.NavyBackground,
    background = ViroColors.NavyBackground,
    onBackground = ViroColors.TextPrimaryDark,
    surface = ViroColors.NavySurface,
    onSurface = ViroColors.TextPrimaryDark,
    surfaceVariant = ViroColors.NavySurfaceElevated,
    onSurfaceVariant = ViroColors.MutedBlue,
    surfaceTint = Color.Transparent,
    inverseSurface = ViroColors.TextPrimaryDark,
    inverseOnSurface = ViroColors.NavyBackground,
    inversePrimary = ViroColors.BlueAccent,
    // Muted, not electric: a resting field or chip is outlined quietly, and
    // only the focused or chosen one takes the accent.
    outline = ViroColors.MutedBlue,
    outlineVariant = ViroColors.NavySurfaceElevated,
    error = ViroColors.RedEndCall,
    surfaceBright = ViroColors.NavySurfaceElevated,
    surfaceDim = ViroColors.NavyBackground,
    surfaceContainerLowest = ViroColors.NavyBackground,
    surfaceContainerLow = ViroColors.NavySurface,
    surfaceContainer = ViroColors.NavySurface,
    surfaceContainerHigh = ViroColors.NavySurfaceElevated,
    surfaceContainerHighest = ViroColors.NavySurfaceElevated,
)

enum class ViroThemeMode { SYSTEM, LIGHT, DARK }
enum class ViroFontScale { SMALL, STANDARD, LARGE }
enum class ViroDensityMode { COMFORTABLE, COMPACT }

@Composable
fun ViroTheme(
    themeMode: ViroThemeMode = ViroThemeMode.DARK,
    fontScale: ViroFontScale = ViroFontScale.STANDARD,
    densityMode: ViroDensityMode = ViroDensityMode.COMFORTABLE,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ViroThemeMode.DARK -> true
        ViroThemeMode.LIGHT -> false
        ViroThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val fontMultiplier = when (fontScale) {
        ViroFontScale.SMALL -> 0.92f
        ViroFontScale.STANDARD -> 1f
        ViroFontScale.LARGE -> 1.12f
    }
    val densityMultiplier = when (densityMode) {
        ViroDensityMode.COMFORTABLE -> 1f
        ViroDensityMode.COMPACT -> 0.94f
    }
    val baseDensity = LocalDensity.current
    CompositionLocalProvider(
        // The semantic tokens every screen reads come from here. Providing
        // the Material scheme alone was the whole bug: it changed what
        // Material draws and nothing about what Viro draws.
        LocalViroPalette provides if (darkTheme) ViroDarkPalette else ViroLightPalette,
        LocalDensity provides Density(
            density = baseDensity.density * densityMultiplier,
            fontScale = baseDensity.fontScale * fontMultiplier,
        ),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) ViroDarkColorScheme else ViroLightColorScheme,
            typography = ViroTypography,
            content = content,
        )
    }
}
