package com.viroreach.feature.auth

/**
 * OTP provider abstraction — swap SMS vendors without rewriting authentication.
 */
interface OtpProvider {
    suspend fun sendOtp(phoneE164: String): OtpChallenge
    suspend fun verifyOtp(challengeId: String, code: String): Boolean
}

data class OtpChallenge(
    val challengeId: String,
    val expiresAt: String
)
