package com.viroreach.feature.discovery

import java.security.SecureRandom
import java.util.UUID

/**
 * Generates rotating ephemeral discovery identifiers.
 * No personally identifying data is encoded.
 */
class EphemeralIdGenerator(
    private val rotationIntervalMs: Long = 5 * 60 * 1000
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
        val random = SecureRandom()
        val bytes = ByteArray(4)
        random.nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "vr-eph-$hex"
    }
}
