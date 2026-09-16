package com.viroreach.app.auth

import android.content.Context
import com.viroreach.app.BuildConfig
import com.viroreach.app.session.SessionManager
import com.viroreach.core.network.ApiDiagnostics
import com.viroreach.core.network.OtpRequestBody
import com.viroreach.core.network.OtpVerifyBody
import com.viroreach.core.security.DeviceIdentityManager
import com.viroreach.feature.contacts.PhoneNormalizer

class AuthRepository(
    private val session: SessionManager,
    private val context: Context,
) {
    private val identity = DeviceIdentityManager(context)

    suspend fun requestOtp(rawPhone: String, defaultRegion: String = "ZM"): Result<OtpChallenge> {
        val normalized = PhoneNormalizer.normalizeToE164(rawPhone, defaultRegion)
            ?: return Result.failure(IllegalArgumentException("Invalid phone number"))
        return try {
            val response = session.api.requestOtp(OtpRequestBody(normalized))
            Result.success(OtpChallenge(response.challengeId, normalized, response.expiresAt))
        } catch (e: Exception) {
            ApiDiagnostics.logFailure(
                ApiDiagnostics.parseFailure("POST", "/api/v1/auth/otp/request", e),
            )
            Result.failure(e)
        }
    }

    suspend fun verifyOtp(challengeId: String, code: String, phoneE164: String): Result<Unit> {
        if (code.isBlank()) {
            return Result.failure(IllegalArgumentException("Verification code required"))
        }
        return try {
            val pubkey = identity.getPublicKeyBase64()
            val verify = session.api.verifyOtp(
                OtpVerifyBody(challengeId, code, pubkey, "ANDROID", BuildConfig.VERSION_NAME),
            )
            session.sessionTokenManager.saveSessionFromAuth(
                verify.accessToken,
                verify.refreshToken,
                verify.userId,
                verify.deviceId,
                phoneE164,
                verify.expiresIn,
            )
            session.onAuthenticationSuccess(phoneE164)
            Result.success(Unit)
        } catch (e: Exception) {
            ApiDiagnostics.logFailure(
                ApiDiagnostics.parseFailure("POST", "/api/v1/auth/otp/verify", e),
            )
            Result.failure(e)
        }
    }
}

data class OtpChallenge(
    val challengeId: String,
    val phoneE164: String,
    val expiresAt: String,
)
