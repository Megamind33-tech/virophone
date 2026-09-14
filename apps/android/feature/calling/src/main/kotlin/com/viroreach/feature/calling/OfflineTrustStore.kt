package com.viroreach.feature.calling

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.viroreach.core.network.OfflineTrustEntry
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Secure local storage for offline trust tokens (Phase 1A POC).
 */
class OfflineTrustStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "viro_offline_trust",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveMaterial(entries: List<OfflineTrustEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("peerUserId", e.peerUserId)
                    .put("trustToken", e.trustToken)
                    .put("epoch", e.epoch)
                    .put("expiresAt", e.expiresAt),
            )
        }
        prefs.edit().putString(KEY_MATERIAL, arr.toString()).apply()
    }

    fun getMaterial(): List<OfflineTrustEntry> {
        val raw = prefs.getString(KEY_MATERIAL, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    OfflineTrustEntry(
                        peerUserId = o.getString("peerUserId"),
                        trustToken = o.getString("trustToken"),
                        epoch = o.getInt("epoch"),
                        expiresAt = o.getString("expiresAt"),
                    ),
                )
            }
        }
    }

    fun computeBindingTag(trustToken: String, ephemeralId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(trustToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val full = mac.doFinal(ephemeralId.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(
            full,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        ).take(22)
    }

    fun resolvePeerByBindingTag(ephemeralId: String, bindingTag: String): String? {
        for (entry in getMaterial()) {
            if (computeBindingTag(entry.trustToken, ephemeralId) == bindingTag) {
                return entry.peerUserId
            }
        }
        return null
    }

    companion object {
        private const val KEY_MATERIAL = "trust_material"
    }
}
