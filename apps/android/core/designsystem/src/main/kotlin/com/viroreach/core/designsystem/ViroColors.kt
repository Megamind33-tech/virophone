package com.viroreach.core.designsystem

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
    val background: Color get() = NavyBackground
    val surface: Color get() = NavySurface
    val surfaceRaised: Color get() = NavySurfaceElevated
    val textPrimary: Color get() = TextPrimaryDark
    val textSecondary: Color get() = MutedBlue
    val textMuted: Color get() = TaglineBlue
    val accent: Color get() = ElectricBlue
    val success: Color get() = GreenAvailable
    val consumerWarning: Color get() = Warning
    val consumerError: Color get() = RedEndCall
    val divider: Color get() = NavySurfaceElevated
}
