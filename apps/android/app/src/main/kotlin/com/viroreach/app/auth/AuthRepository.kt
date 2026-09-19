package com.viroreach.app.auth

import android.content.Context
import com.viroreach.app.BuildConfig
import com.viroreach.app.session.SessionManager
import com.viroreach.core.network.ApiDiagnostics
import com.viroreach.core.network.OtpRequestBody
import com.viroreach.core.network.OtpVerifyBody
import com.viroreach.core.network.FirebaseSignInBody
import com.viroreach.core.network.PhoneLinkRequestBody
import com.viroreach.core.network.PhoneLinkVerifyBody
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
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
            // Before anything is cached or read for this account, drop data
            // belonging to a different one that is still on disk.
            session.enforceAccountBoundary(verify.userId)
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

    /**
     * Email/password sign-in via Firebase.
     *
     * Firebase authenticates the credentials and hands back an ID token; the
     * server verifies that token and issues an ordinary Viro session, so from
     * here on the app behaves exactly as it does after phone-OTP. The password
     * itself never reaches Viro's servers — only the signed token does.
     *
     * [register] true creates the account, false signs in to an existing one.
     */
    /** Sends the OTP that proves the user holds the number being linked. */
    suspend fun requestPhoneLink(phoneE164: String): Result<String> = runCatching {
        session.api.requestPhoneLink(PhoneLinkRequestBody(phoneE164)).challengeId
    }.onFailure {
        ApiDiagnostics.logFailure(
            ApiDiagnostics.parseFailure("POST", "/api/v1/auth/link/phone/request", it),
        )
    }

    /**
     * Confirms the code and connects the number.
     *
     * When the server reports "adopted" the signed-in identity CHANGES: the
     * number already had an account and this empty one was folded into it. The
     * old tokens are for a user id that no longer exists, so the new session is
     * installed here and the account boundary is re-evaluated — the cached data
     * on this device belonged to the account that just went away.
     */
    suspend fun verifyPhoneLink(challengeId: String, code: String): Result<String> =
        runCatching {
            val res = session.api.verifyPhoneLink(PhoneLinkVerifyBody(challengeId, code))
            val newAccess = res.accessToken
            val newRefresh = res.refreshToken
            if (res.outcome == "adopted" && newAccess != null && newRefresh != null) {
                session.enforceAccountBoundary(res.userId)
                session.sessionTokenManager.saveSessionFromAuth(
                    newAccess,
                    newRefresh,
                    res.userId,
                    session.tokenStore.getDeviceId().orEmpty(),
                    res.phoneE164,
                    res.expiresIn ?: 900,
                )
            }
            res.outcome
        }.onFailure {
            ApiDiagnostics.logFailure(
                ApiDiagnostics.parseFailure("POST", "/api/v1/auth/link/phone/verify", it),
            )
        }

    suspend fun signInWithEmail(
        email: String,
        password: String,
        register: Boolean,
    ): Result<Unit> {
        val trimmed = email.trim()
        if (trimmed.isBlank() || password.isBlank()) {
            return Result.failure(IllegalArgumentException("Email and password required"))
        }
        return try {
            val auth = FirebaseAuth.getInstance()
            val result = if (register) {
                auth.createUserWithEmailAndPassword(trimmed, password).await()
            } else {
                auth.signInWithEmailAndPassword(trimmed, password).await()
            }
            val idToken = result.user?.getIdToken(false)?.await()?.token
                ?: return Result.failure(IllegalStateException("Firebase returned no ID token"))

            val pubkey = identity.getPublicKeyBase64()
            val signIn = session.api.firebaseSignIn(
                FirebaseSignInBody(idToken, pubkey, "ANDROID", BuildConfig.VERSION_NAME),
            )
            session.enforceAccountBoundary(signIn.userId)
            session.sessionTokenManager.saveSessionFromAuth(
                signIn.accessToken,
                signIn.refreshToken,
                signIn.userId,
                signIn.deviceId,
                // An email account has no phone number; the session stores the
                // email in its place so the UI has something to show for "who
                // am I". Contact discovery by phone will not find this account.
                signIn.email,
                signIn.expiresIn,
            )
            session.onAuthenticationSuccess(signIn.email)
            Result.success(Unit)
        } catch (e: Exception) {
            ApiDiagnostics.logFailure(
                ApiDiagnostics.parseFailure("POST", "/api/v1/auth/firebase/signin", e),
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
