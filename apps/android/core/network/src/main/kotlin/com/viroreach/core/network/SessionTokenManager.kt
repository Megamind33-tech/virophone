package com.viroreach.core.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Single authoritative session/token source with single-flight refresh.
 * All authenticated HTTP and WSS token reads should go through here.
 */
class SessionTokenManager(
    private val tokenStore: TokenStore,
    baseUrl: String = BuildConfig.API_BASE_URL,
) {
    private val refreshMutex = Mutex()

    private val refreshApi: ViroApiService = Retrofit.Builder()
        .baseUrl(baseUrl.ensureTrailingSlash())
        .client(
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(),
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ViroApiService::class.java)

    fun saveSessionFromAuth(
        accessToken: String,
        refreshToken: String,
        userId: String,
        deviceId: String,
        phoneE164: String?,
        expiresInSeconds: Int,
        email: String? = null,
    ) {
        tokenStore.saveSession(accessToken, refreshToken, userId, deviceId, phoneE164, email)
        tokenStore.saveAccessTokenExpiry(System.currentTimeMillis() + expiresInSeconds * 1000L)
    }

    fun updateTokens(accessToken: String, refreshToken: String, expiresInSeconds: Int) {
        val userId = tokenStore.getUserId() ?: return
        val deviceId = tokenStore.getDeviceId() ?: return
        tokenStore.saveSession(
            accessToken,
            refreshToken,
            userId,
            deviceId,
            tokenStore.getAuthenticatedPhoneE164(),
            tokenStore.getAuthenticatedEmail(),
        )
        tokenStore.saveAccessTokenExpiry(System.currentTimeMillis() + expiresInSeconds * 1000L)
    }

    suspend fun getAccessTokenForRequest(): String? {
        val token = tokenStore.getAccessToken()
        if (token != null && !isAccessTokenExpiringSoon()) return token
        if (!tokenStore.hasPersistedSession()) return token
        return refreshMutex.withLock {
            val current = tokenStore.getAccessToken()
            if (current != null && !isAccessTokenExpiringSoon()) return@withLock current
            refreshLocked()
        }
    }

    suspend fun refreshAfter401(): String? = refreshMutex.withLock { refreshLocked() }

    /**
     * A persisted refresh token means this install already registered — not a fresh signup.
     * Access tokens may be expired or missing after an app update; refresh silently on restore.
     */
    fun isAuthenticated(): Boolean = tokenStore.hasPersistedSession()

    /** Attempt silent refresh so updaters skip the signup screen. Returns false if session is dead. */
    suspend fun restorePersistedSession(): Boolean {
        if (!tokenStore.hasPersistedSession()) return false
        return getAccessTokenForRequest() != null
    }

    private fun isAccessTokenExpiringSoon(): Boolean {
        val expiry = tokenStore.getAccessTokenExpiry() ?: return false
        return System.currentTimeMillis() >= expiry - REFRESH_SKEW_MS
    }

    private suspend fun refreshLocked(): String? {
        val refresh = tokenStore.getRefreshToken() ?: return null
        return try {
            val resp = refreshApi.refreshToken(RefreshBody(refresh))
            updateTokens(resp.accessToken, resp.refreshToken, resp.expiresIn)
            resp.accessToken
        } catch (_: Exception) {
            null
        }
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"

    companion object {
        private const val REFRESH_SKEW_MS = 60_000L
    }
}
