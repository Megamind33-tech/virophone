package com.viroreach.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.viroreach.app.root.ViroReachRoot
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroDensityMode
import com.viroreach.core.designsystem.ViroFontScale
import com.viroreach.core.designsystem.ViroTheme
import com.viroreach.core.designsystem.ViroThemeMode
import com.viroreach.app.personalization.ThemeMode
import com.viroreach.app.personalization.FontSizePreference
import com.viroreach.app.personalization.DensityPreference
import com.viroreach.app.personalization.WallpaperType
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.viroreach.core.designsystem.components.LocalViroWallpaper
import com.viroreach.core.designsystem.components.ViroWallpaperConfig

// A FragmentActivity (still a ComponentActivity) because the biometric prompt
// behind chat lock needs one.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingCallIntent(intent)
        handleOpenIntent(intent)
        val session = SessionManager.get(this)
        setContent {
            val prefs by session.appearanceManager.preferences.collectAsState(
                initial = com.viroreach.app.personalization.AppearancePreferences(),
            )
            val wallpaperConfig = ViroWallpaperConfig(
                imageUri = when (prefs.wallpaperType) {
                    WallpaperType.DEVICE, WallpaperType.BUILTIN -> prefs.wallpaperReference.takeIf { it.isNotBlank() }
                    else -> null
                },
                dimAmount = prefs.wallpaperDimAmount,
                blurRadiusDp = prefs.wallpaperBlurRadius,
                contrast = prefs.wallpaperContrast,
            )
            CompositionLocalProvider(LocalViroWallpaper provides wallpaperConfig) {
                ViroTheme(
                    themeMode = when (prefs.themeMode) {
                        ThemeMode.SYSTEM -> ViroThemeMode.SYSTEM
                        ThemeMode.LIGHT -> ViroThemeMode.LIGHT
                        ThemeMode.DARK -> ViroThemeMode.DARK
                    },
                    fontScale = when (prefs.fontSize) {
                        FontSizePreference.SMALL -> ViroFontScale.SMALL
                        FontSizePreference.STANDARD -> ViroFontScale.STANDARD
                        FontSizePreference.LARGE -> ViroFontScale.LARGE
                    },
                    densityMode = when (prefs.density) {
                        DensityPreference.COMFORTABLE -> ViroDensityMode.COMFORTABLE
                        DensityPreference.COMPACT -> ViroDensityMode.COMPACT
                    },
                ) {
                    ViroReachRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallIntent(intent)
        handleOpenIntent(intent)
    }

    /** A notification tap: open a chat or the Connections dashboard. */
    private fun handleOpenIntent(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_OPEN) ?: return
        AppNavigation.request(
            AppNavigation.Target(
                screen = target,
                peerUserId = intent.getStringExtra(EXTRA_PEER_USER_ID),
            ),
        )
        intent.removeExtra(EXTRA_OPEN)
    }

    private fun handleIncomingCallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_INCOMING_CALL, false) != true) return
        SessionManager.get(this).incomingCallNotifier.dismiss()
    }

    companion object {
        const val EXTRA_INCOMING_CALL = "extra_incoming_call"
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_OPEN = "extra_open"
        const val EXTRA_PEER_USER_ID = "extra_peer_user_id"
        const val OPEN_CHAT = "chat"
        const val OPEN_CONNECTIONS = "connections"

        fun incomingCallIntent(context: Context, callId: String, callerName: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_INCOMING_CALL, true)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALLER_NAME, callerName)
            }
    }
}
