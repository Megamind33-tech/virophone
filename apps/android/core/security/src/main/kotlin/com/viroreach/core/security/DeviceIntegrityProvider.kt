package com.viroreach.core.security

/**
 * Play Integrity integration boundary.
 * Production policy can use Play Integrity; local/dev builds skip it.
 */
interface DeviceIntegrityProvider {
    suspend fun checkIntegrity(): IntegrityResult
}

data class IntegrityResult(
    val status: IntegrityStatus,
    val riskScore: Float = 0f
)

enum class IntegrityStatus {
    PASSED, FAILED, SKIPPED, UNKNOWN
}

class StubDeviceIntegrityProvider : DeviceIntegrityProvider {
    override suspend fun checkIntegrity(): IntegrityResult {
        return IntegrityResult(IntegrityStatus.SKIPPED)
    }
}
