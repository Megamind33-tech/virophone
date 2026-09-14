package com.viroreach.feature.discovery

import java.security.SecureRandom
import java.util.Base64

/**
 * 128-bit cryptographically random ephemeral discovery identifiers.
 * Format: vr1_<url-safe-base64-no-padding>
 * NOT derived from phone, Viro ID, device ID, MAC, or IP.
 */
class EphemeralIdGenerator(
    private val rotationIntervalMs: Long = 15 * 60 * 1000L
) {
    private var currentId: String = generate()
    private var generatedAt: Long = System.currentTimeMillis()

    fun getCurrentId(): String {
        if (System.currentTimeMillis() - generatedAt > rotationIntervalMs) {
            rotate()
        }
        return currentId
    }

    fun rotate(): String {
        currentId = generate()
        generatedAt = System.currentTimeMillis()
        return currentId
    }

    private fun generate(): String {
        val bytes = ByteArray(16) // 128 bits
        SecureRandom().nextBytes(bytes)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return "vr1_$encoded"
    }
}
