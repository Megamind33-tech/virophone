package com.viroreach.app.personalization

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore by preferencesDataStore("viro_appearance")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class FontSizePreference { SMALL, STANDARD, LARGE }

enum class DensityPreference { COMFORTABLE, COMPACT }

enum class CallScreenBackground { DEFAULT, WALLPAPER, CONTACT_PHOTO }

enum class WallpaperType { DEFAULT, SOLID, BUILTIN, DEVICE }

data class AppearancePreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontSize: FontSizePreference = FontSizePreference.STANDARD,
    val density: DensityPreference = DensityPreference.COMFORTABLE,
    val callScreenBackground: CallScreenBackground = CallScreenBackground.DEFAULT,
    val wallpaperType: WallpaperType = WallpaperType.DEFAULT,
    val wallpaperReference: String = "",
    val wallpaperDimAmount: Float = 0.35f,
    val wallpaperBlurRadius: Float = 12f,
    val wallpaperContrast: Float = 1.1f,
)

class ViroAppearanceManager(context: Context) {
    private val store = context.applicationContext.appearanceDataStore

    val preferences: Flow<AppearancePreferences> = store.data.map { prefs ->
        AppearancePreferences(
            themeMode = ThemeMode.valueOf(prefs[KEY_THEME] ?: ThemeMode.SYSTEM.name),
            fontSize = FontSizePreference.valueOf(prefs[KEY_FONT] ?: FontSizePreference.STANDARD.name),
            density = DensityPreference.valueOf(prefs[KEY_DENSITY] ?: DensityPreference.COMFORTABLE.name),
            callScreenBackground = CallScreenBackground.valueOf(
                prefs[KEY_CALL_BG] ?: CallScreenBackground.DEFAULT.name,
            ),
            wallpaperType = WallpaperType.valueOf(prefs[KEY_WP_TYPE] ?: WallpaperType.DEFAULT.name),
            wallpaperReference = prefs[KEY_WP_REF] ?: "",
            wallpaperDimAmount = prefs[KEY_WP_DIM] ?: 0.35f,
            wallpaperBlurRadius = prefs[KEY_WP_BLUR] ?: 12f,
            wallpaperContrast = prefs[KEY_WP_CONTRAST] ?: 1.1f,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit(KEY_THEME, mode.name)
    suspend fun setFontSize(size: FontSizePreference) = edit(KEY_FONT, size.name)
    suspend fun setDensity(density: DensityPreference) = edit(KEY_DENSITY, density.name)
    suspend fun setCallScreenBackground(bg: CallScreenBackground) = edit(KEY_CALL_BG, bg.name)
    suspend fun setWallpaper(type: WallpaperType, reference: String, dim: Float) {
        store.edit { prefs ->
            prefs[KEY_WP_TYPE] = type.name
            prefs[KEY_WP_REF] = reference
            prefs[KEY_WP_DIM] = dim
        }
    }

    suspend fun setWallpaperDim(amount: Float) {
        store.edit { it[KEY_WP_DIM] = amount.coerceIn(0f, 0.85f) }
    }

    suspend fun setWallpaperBlur(radius: Float) {
        store.edit { it[KEY_WP_BLUR] = radius.coerceIn(0f, 40f) }
    }

    suspend fun setWallpaperContrast(contrast: Float) {
        store.edit { it[KEY_WP_CONTRAST] = contrast.coerceIn(0.6f, 1.8f) }
    }

    private suspend fun edit(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        store.edit { it[key] = value }
    }

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_FONT = stringPreferencesKey("font_size")
        private val KEY_DENSITY = stringPreferencesKey("density")
        private val KEY_CALL_BG = stringPreferencesKey("call_screen_bg")
        private val KEY_WP_TYPE = stringPreferencesKey("wallpaper_type")
        private val KEY_WP_REF = stringPreferencesKey("wallpaper_reference")
        private val KEY_WP_DIM = floatPreferencesKey("wallpaper_dim")
        private val KEY_WP_BLUR = floatPreferencesKey("wallpaper_blur")
        private val KEY_WP_CONTRAST = floatPreferencesKey("wallpaper_contrast")
    }
}
