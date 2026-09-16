package com.viroreach.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val ViroLightColorScheme = lightColorScheme(
    primary = ViroColors.BluePrimary,
    onPrimary = ViroColors.SurfaceLight,
    secondary = ViroColors.BlueAccent,
    background = ViroColors.WarmBackgroundLight,
    onBackground = ViroColors.TextPrimaryLight,
    surface = ViroColors.SurfaceLight,
    onSurface = ViroColors.TextPrimaryLight,
    error = ViroColors.Error,
)

private val ViroDarkColorScheme = darkColorScheme(
    primary = ViroColors.ElectricBlue,
    onPrimary = ViroColors.TextPrimaryDark,
    primaryContainer = ViroColors.NavySurfaceElevated,
    secondary = ViroColors.BlueAccent,
    background = ViroColors.NavyBackground,
    onBackground = ViroColors.TextPrimaryDark,
    surface = ViroColors.NavySurface,
    onSurface = ViroColors.TextPrimaryDark,
    onSurfaceVariant = ViroColors.MutedBlue,
    outline = ViroColors.ElectricBlue,
    error = ViroColors.RedEndCall,
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
