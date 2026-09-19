package com.viroreach.core.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "viro_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveSession(
        accessToken: String,
        refreshToken: String,
        userId: String,
        deviceId: String,
        phoneE164: String? = null,
    ) {
        val editor = prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_DEVICE_ID, deviceId)
        if (phoneE164 != null) {
            editor.putString(KEY_PHONE, phoneE164)
        }
        editor.apply()
    }

    /** True when this install has ever completed registration (survives app updates). */
    fun hasPersistedSession(): Boolean =
        !getRefreshToken().isNullOrBlank() && !getUserId().isNullOrBlank()

    fun saveAccessTokenExpiry(expiresAtEpochMs: Long) {
        prefs.edit().putLong(KEY_ACCESS_EXPIRY, expiresAtEpochMs).apply()
    }

    fun getAccessTokenExpiry(): Long? {
        val v = prefs.getLong(KEY_ACCESS_EXPIRY, -1L)
        return if (v > 0) v else null
    }

    fun getAuthenticatedPhoneE164(): String? = prefs.getString(KEY_PHONE, null)

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS, null)
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)
    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)
    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    /** Clears session tokens only — preserves authenticated phone for engineering re-login. */
    /**
     * The last account to hold a session on this device. Survives clearSession()
     * on purpose: it is how the app notices that the account signing in now is
     * not the one whose cached contacts, history and messages are still on disk.
     */
    fun getLastUserId(): String? = prefs.getString(KEY_LAST_USER_ID, null)

    fun setLastUserId(userId: String) {
        prefs.edit().putString(KEY_LAST_USER_ID, userId).apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_USER_ID)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_ACCESS_EXPIRY)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PHONE = "authenticated_phone_e164"
        private const val KEY_ACCESS_EXPIRY = "access_token_expiry_ms"
        private const val KEY_LAST_USER_ID = "last_user_id"
    }
}
