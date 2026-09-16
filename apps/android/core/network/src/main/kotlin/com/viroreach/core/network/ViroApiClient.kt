package com.viroreach.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(SanitizedLoggingInterceptor())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val api: ViroApiService = Retrofit.Builder()
        .baseUrl(baseUrl.ensureTrailingSlash())
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ViroApiService::class.java)

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"
}
