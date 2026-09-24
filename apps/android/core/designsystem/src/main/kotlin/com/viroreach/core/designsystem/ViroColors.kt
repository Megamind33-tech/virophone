package com.viroreach.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

object ViroColors {
    /** Raw palette */
    val NavyBackground = Color(0xFF00122C)
    val NavySurface = Color(0xFF001A3F)
    val NavySurfaceElevated = Color(0xFF0A2548)
    val ElectricBlue = Color(0xFF007AFF)
    val ElectricBlueGlow = Color(0xFF1E90FF)
    val MutedBlue = Color(0xFF6B8CAE)
    val TaglineBlue = Color(0xFF7EB8FF)

    val BluePrimary = Color(0xFF007AFF)
    val BlueAccent = Color(0xFF42A5F5)
    val BlueDark = Color(0xFF0D47A1)
    val GreenAvailable = Color(0xFF4CD964)
    val RedEndCall = Color(0xFFFF3B30)
    val WarmBackgroundLight = Color(0xFFF7F8FA)
    val SurfaceLight = Color(0xFFFFFFFF)
    val TextPrimaryLight = Color(0xFF1A1C1E)
    val TextSecondaryLight = Color(0xFF5F6368)
    val NearBlack = Color(0xFF0B0D10)
    val SurfaceDark = Color(0xFF161A20)
    val TextPrimaryDark = Color(0xFFF2F4F7)
    val TextSecondaryDark = Color(0xFF9AA3AD)
    val Error = Color(0xFFC62828)
    val Warning = Color(0xFFF9A825)

    /** Semantic tokens — use in consumer UI */
    /**
     * Read from whatever palette the theme is providing, rather than naming
     * a colour outright.
     *
     * These were plain getters returning the navy values, so choosing Light
     * changed the Material scheme and nothing else: every screen went on
     * asking for the dark ones and got them. The setting existed and did
     * nothing.
     *
     * The raw palette above stays fixed on purpose. Somewhere that asks for
     * NavySurface by name means that colour: a Moment room is dark the way a
     * cinema is dark, and should not turn white because the phone did.
     */
    val background: Color @Composable @ReadOnlyComposable get() = LocalViroPalette.current.background
    val surface: Color @Composable @ReadOnlyComposable get() = LocalViroPalette.current.surface
    val surfaceRaised: Color @Composable @ReadOnlyComposable get() = LocalViroPalette.current.surfaceRaised
    val textPrimary: Color @Composable @ReadOnlyComposable get() = LocalViroPalette.current.textPrimary
    val textSecondary: Color @Composable @ReadOnlyComposable get() = LocalViroPalette.current.textSecondary
    val textMuted: Color @Composable @ReadOnlyComposable get() = LocalViroPalette.current.textMuted
    val accent: Color @Composable @ReadOnlyComposable get() = LocalViroPalette.current.accent
    val success: Color @Composable @ReadOnlyComposable get() = LocalViroPalette.current.success
    val consumerWarning: Color @Composable @ReadOnlyComposable get() = LocalViroPalette.current.consumerWarning
    val consumerError: Color @Composable @ReadOnlyComposable get() = LocalViroPalette.current.consumerError
    val divider: Color @Composable @ReadOnlyComposable get() = LocalViroPalette.current.divider
    val onAccent: Color @Composable @ReadOnlyComposable get() = LocalViroPalette.current.onAccent
    val isLight: Boolean @Composable @ReadOnlyComposable get() = LocalViroPalette.current.isLight
}

/**
 * The colours a screen means, for one appearance.
 *
 * Named for the job each one does rather than what it looks like, so a screen
 * reads correctly in either: "the text you read" and "the surface behind it",
 * not "navy" and "near-white".
 */
@Immutable
data class ViroPalette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val success: Color,
    val consumerWarning: Color,
    val consumerError: Color,
    val divider: Color,
    /** Daylight, so anything drawn over a photo knows which way to shade it. */
    val isLight: Boolean = false,
    /** What sits on the accent colour: white in both, because the accent is a strong blue in both. */
    val onAccent: Color = Color.White,
)

/** Viro at night, which is how it has always looked and remains the default. */
val ViroDarkPalette = ViroPalette(
    background = ViroColors.NavyBackground,
    surface = ViroColors.NavySurface,
    surfaceRaised = ViroColors.NavySurfaceElevated,
    textPrimary = ViroColors.TextPrimaryDark,
    textSecondary = ViroColors.MutedBlue,
    textMuted = ViroColors.TaglineBlue,
    accent = ViroColors.ElectricBlue,
    success = ViroColors.GreenAvailable,
    consumerWarning = ViroColors.Warning,
    consumerError = ViroColors.RedEndCall,
    divider = ViroColors.NavySurfaceElevated,
)

/**
 * Viro in daylight.
 *
 * Not the dark one inverted. Muted blue on navy is a quiet grey; the same blue
 * on white is a pale wash nobody can read, so the daytime secondary and muted
 * text are proper greys. The accent darkens for the same reason, and the
 * warning colour especially: amber on white is close to invisible.
 */
val ViroLightPalette = ViroPalette(
    background = ViroColors.WarmBackgroundLight,
    surface = ViroColors.SurfaceLight,
    surfaceRaised = Color(0xFFEDF1F6),
    textPrimary = ViroColors.TextPrimaryLight,
    textSecondary = ViroColors.TextSecondaryLight,
    textMuted = Color(0xFF7A8795),
    accent = ViroColors.BluePrimary,
    success = Color(0xFF1B8B3A),
    consumerWarning = Color(0xFF8A6100),
    consumerError = Color(0xFFB3261E),
    divider = Color(0xFFDDE3EA),
    isLight = true,
)

/**
 * Dark unless a theme says otherwise, so anything composed outside ViroTheme
 * still looks like Viro rather than unstyled.
 */
val LocalViroPalette = staticCompositionLocalOf { ViroDarkPalette }
