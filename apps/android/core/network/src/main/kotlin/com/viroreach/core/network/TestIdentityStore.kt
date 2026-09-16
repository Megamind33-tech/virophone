package com.viroreach.core.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the hardware-test phone identity per physical installation.
 * Separate from session tokens — survives logout, not Keystore reset.
 */
class TestIdentityStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "viro_test_identity",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun savePhoneE164(phoneE164: String) {
        prefs.edit()
            .putString(KEY_PHONE, phoneE164)
            .putBoolean(KEY_REGISTERED, true)
            .apply()
    }

    fun getPhoneE164(): String? = prefs.getString(KEY_PHONE, null)

    fun hasRegisteredBefore(): Boolean =
        prefs.getBoolean(KEY_REGISTERED, false) || !getPhoneE164().isNullOrBlank()

    fun clear() {
        prefs.edit()
            .remove(KEY_PHONE)
            .remove(KEY_REGISTERED)
            .apply()
    }

    companion object {
        private const val KEY_PHONE = "phone_e164"
        private const val KEY_REGISTERED = "registered_once"
    }
}
