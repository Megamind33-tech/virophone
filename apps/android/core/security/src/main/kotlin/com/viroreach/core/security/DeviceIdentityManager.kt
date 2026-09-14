package com.viroreach.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.util.Base64

/**
 * Manages device cryptographic identity.
 * Private key NEVER leaves Android Keystore.
 */
class DeviceIdentityManager(private val context: Context) {
    companion object {
        private const val KEY_ALIAS = "viro_reach_device_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    fun getOrCreateKeyPair(): DeviceKeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGen = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
            )
            keyGen.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            keyGen.generateKeyPair()
        }

        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val publicKeyBytes = entry.certificate.publicKey.encoded
        val publicKeyBase64 = Base64.getEncoder().encodeToString(publicKeyBytes)

        return DeviceKeyPair(
            publicKeyBase64 = publicKeyBase64,
            publicKey = entry.certificate.publicKey
        )
    }

    fun getPublicKeyBase64(): String = getOrCreateKeyPair().publicKeyBase64
}

data class DeviceKeyPair(
    val publicKeyBase64: String,
    val publicKey: PublicKey
)
