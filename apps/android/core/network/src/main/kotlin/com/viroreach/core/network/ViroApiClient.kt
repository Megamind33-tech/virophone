package com.viroreach.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ViroApiClient(
    private val sessionTokenManager: SessionTokenManager,
    baseUrl: String = BuildConfig.API_BASE_URL,
) {
    private val authInterceptor = Interceptor { chain ->
        val token = runBlocking { sessionTokenManager.getAccessTokenForRequest() }
        var request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        var response = chain.proceed(request)
        if (response.code == 401 && token != null) {
            response.close()
            val newToken = runBlocking { sessionTokenManager.refreshAfter401() }
            if (newToken != null) {
                request = chain.request().newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
                response = chain.proceed(request)
            }
        }
        response
    }

    // "Today", "this week" and "every Friday" are the user's local days; the
    // relationship engine needs to know which zone that is.
    private val timezoneInterceptor = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("X-Timezone", TimeZone.getDefault().id)
                .build(),
        )
    }

    /** Authenticated client, also used for media uploads and downloads. */
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(timezoneInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(SanitizedLoggingInterceptor())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val baseUrl: String = baseUrl.ensureTrailingSlash()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(this.baseUrl)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: ViroApiService = retrofit.create(ViroApiService::class.java)

    /** Messaging v2, Loops and relationships. */
    val messaging: ViroMessagingApi = retrofit.create(ViroMessagingApi::class.java)

    /** The end-to-end encryption key directory. */
    val keys: ViroKeysApi = retrofit.create(ViroKeysApi::class.java)

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"
}
