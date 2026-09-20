package com.viroreach.app.messaging

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.mediaSettingsStore by preferencesDataStore("viro_media_settings")

/** What downloads by itself. Documents and voice notes always wait to be tapped. */
enum class AutoKind { PHOTO, GIF }

/**
 * Data costs money here, so what arrives by itself is a choice. Photos come in
 * on their own by default — a chat where pictures never appear feels broken —
 * and GIFs, which are far heavier, wait for Wi-Fi.
 */
data class MediaPreferences(
    val photosOnMobile: Boolean = true,
    val photosOnWifi: Boolean = true,
    val gifsOnMobile: Boolean = false,
    val gifsOnWifi: Boolean = true,
)

/** The rule itself, kept apart from Android so it can be tested plainly. */
fun shouldAutoDownload(kind: AutoKind, metered: Boolean, prefs: MediaPreferences): Boolean = when (kind) {
    AutoKind.PHOTO -> if (metered) prefs.photosOnMobile else prefs.photosOnWifi
    AutoKind.GIF -> if (metered) prefs.gifsOnMobile else prefs.gifsOnWifi
}

class MediaSettings(private val context: Context) {
    private val store = context.applicationContext.mediaSettingsStore

    val preferences: Flow<MediaPreferences> = store.data.map { p ->
        MediaPreferences(
            photosOnMobile = p[PHOTOS_MOBILE] ?: true,
            photosOnWifi = p[PHOTOS_WIFI] ?: true,
            gifsOnMobile = p[GIFS_MOBILE] ?: false,
            gifsOnWifi = p[GIFS_WIFI] ?: true,
        )
    }

    suspend fun set(kind: AutoKind, metered: Boolean, on: Boolean) {
        val key = when {
            kind == AutoKind.PHOTO && metered -> PHOTOS_MOBILE
            kind == AutoKind.PHOTO -> PHOTOS_WIFI
            metered -> GIFS_MOBILE
            else -> GIFS_WIFI
        }
        store.edit { it[key] = on }
    }

    /**
     * Whether the connection being used now costs by the megabyte. A metered
     * Wi-Fi hotspot counts as mobile data, which is the point of asking the
     * system rather than looking at the network type.
     */
    fun onMeteredConnection(): Boolean =
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        }.getOrDefault(true)

    private companion object {
        val PHOTOS_MOBILE = booleanPreferencesKey("photos_mobile")
        val PHOTOS_WIFI = booleanPreferencesKey("photos_wifi")
        val GIFS_MOBILE = booleanPreferencesKey("gifs_mobile")
        val GIFS_WIFI = booleanPreferencesKey("gifs_wifi")
    }
}
