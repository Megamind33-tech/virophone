package com.viroreach.app.personalization

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroBackButton
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import kotlinx.coroutines.launch

@Composable
fun AppearanceScreen(session: SessionManager, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = session.appearanceManager
    val prefs by manager.preferences.collectAsState(
        initial = AppearancePreferences(),
    )
    val wallpaperLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scope.launch {
                manager.setWallpaper(WallpaperType.DEVICE, it.toString(), prefs.wallpaperDimAmount)
            }
        }
    }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(ViroSpacing.md),
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    ViroBackButton(onClick = onBack)
                    Text("Appearance", style = MaterialTheme.typography.headlineMedium, color = ViroColors.textPrimary)
                }
                Spacer(Modifier.height(ViroSpacing.lg))
                Section("Theme") {
                    ChoiceRow("System", prefs.themeMode == ThemeMode.SYSTEM) {
                        scope.launch { manager.setThemeMode(ThemeMode.SYSTEM) }
                    }
                    ChoiceRow("Light", prefs.themeMode == ThemeMode.LIGHT) {
                        scope.launch { manager.setThemeMode(ThemeMode.LIGHT) }
                    }
                    ChoiceRow("Dark", prefs.themeMode == ThemeMode.DARK) {
                        scope.launch { manager.setThemeMode(ThemeMode.DARK) }
                    }
                }
                Section("Font size") {
                    ChoiceRow("Small", prefs.fontSize == FontSizePreference.SMALL) {
                        scope.launch { manager.setFontSize(FontSizePreference.SMALL) }
                    }
                    ChoiceRow("Standard", prefs.fontSize == FontSizePreference.STANDARD) {
                        scope.launch { manager.setFontSize(FontSizePreference.STANDARD) }
                    }
                    ChoiceRow("Large", prefs.fontSize == FontSizePreference.LARGE) {
                        scope.launch { manager.setFontSize(FontSizePreference.LARGE) }
                    }
                }
                Section("App density") {
                    ChoiceRow("Comfortable", prefs.density == DensityPreference.COMFORTABLE) {
                        scope.launch { manager.setDensity(DensityPreference.COMFORTABLE) }
                    }
                    ChoiceRow("Compact", prefs.density == DensityPreference.COMPACT) {
                        scope.launch { manager.setDensity(DensityPreference.COMPACT) }
                    }
                }
                Section("Call screen background") {
                    ChoiceRow("Default", prefs.callScreenBackground == CallScreenBackground.DEFAULT) {
                        scope.launch { manager.setCallScreenBackground(CallScreenBackground.DEFAULT) }
                    }
                    ChoiceRow("Wallpaper", prefs.callScreenBackground == CallScreenBackground.WALLPAPER) {
                        scope.launch { manager.setCallScreenBackground(CallScreenBackground.WALLPAPER) }
                    }
                    ChoiceRow("Contact photo", prefs.callScreenBackground == CallScreenBackground.CONTACT_PHOTO) {
                        scope.launch { manager.setCallScreenBackground(CallScreenBackground.CONTACT_PHOTO) }
                    }
                }
                Section("App background") {
                    ChoiceRow("Default", prefs.wallpaperType == WallpaperType.DEFAULT) {
                        scope.launch { manager.setWallpaper(WallpaperType.DEFAULT, "", prefs.wallpaperDimAmount) }
                    }
                    ChoiceRow("Choose wallpaper", prefs.wallpaperType == WallpaperType.DEVICE) {
                        wallpaperLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    ChoiceRow("Remove wallpaper", false) {
                        scope.launch { manager.setWallpaper(WallpaperType.DEFAULT, "", prefs.wallpaperDimAmount) }
                    }
                    if (prefs.wallpaperType == WallpaperType.DEVICE && prefs.wallpaperReference.isNotBlank()) {
                        Spacer(Modifier.height(ViroSpacing.sm))
                        Text("Dim overlay", color = ViroColors.textSecondary, style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = prefs.wallpaperDimAmount,
                            onValueChange = { scope.launch { manager.setWallpaperDim(it) } },
                            valueRange = 0f..0.75f,
                        )
                        Text("Blur", color = ViroColors.textSecondary, style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = prefs.wallpaperBlurRadius,
                            onValueChange = { scope.launch { manager.setWallpaperBlur(it) } },
                            valueRange = 0f..32f,
                        )
                        Text("Contrast", color = ViroColors.textSecondary, style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = prefs.wallpaperContrast,
                            onValueChange = { scope.launch { manager.setWallpaperContrast(it) } },
                            valueRange = 0.7f..1.6f,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(bottom = ViroSpacing.lg)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = ViroColors.textMuted)
        Spacer(Modifier.height(ViroSpacing.sm))
        Card { Column(Modifier.padding(ViroSpacing.md), content = content) }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = ViroSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = ViroColors.textPrimary)
        if (selected) Text("✓", color = ViroColors.accent)
    }
}
