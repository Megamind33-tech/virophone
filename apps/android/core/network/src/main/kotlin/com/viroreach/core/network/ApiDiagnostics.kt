package com.viroreach.core.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

enum class StageStatus { NOT_STARTED, RUNNING, PASS, FAIL }

data class SanitizedApiFailure(
    val method: String,
    val path: String,
    val httpStatus: Int,
    val errorCode: String?,
    val message: String?,
    val requestId: String?,
) {
    fun summary(): String = buildString {
        append("$method $path → HTTP $httpStatus")
        errorCode?.let { append(" [$it]") }
        message?.let { append(": $it") }
        requestId?.let { append(" (req=$it)") }
    }
}

object ApiDiagnostics {
    private const val TAG = "ViroApiDiag"
    private val gson = Gson()

    fun parseFailure(method: String, path: String, throwable: Throwable): SanitizedApiFailure {
        if (throwable is HttpException) {
            val body = throwable.response()?.errorBody()?.string()
            val parsed = body?.let { parseErrorBody(it) }
            return SanitizedApiFailure(
                method = method,
                path = path,
                httpStatus = throwable.code(),
                errorCode = parsed?.first,
                message = parsed?.second,
                requestId = parsed?.third ?: throwable.response()?.headers()?.get("x-request-id"),
            )
        }
        return SanitizedApiFailure(
            method = method,
            path = path,
            httpStatus = -1,
            errorCode = "NETWORK_ERROR",
            message = throwable.message?.take(200),
            requestId = null,
        )
    }

    private fun parseErrorBody(body: String): Triple<String?, String?, String?>? {
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
            Triple(
                json.get("code")?.asString,
                json.get("message")?.asString,
                json.get("requestId")?.asString,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun logFailure(failure: SanitizedApiFailure) {
        Log.w(TAG, failure.summary())
    }

    suspend fun checkHealth(baseUrl: String = BuildConfig.API_BASE_URL): HealthCheckResult =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + "/health/live"
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            try {
                val response = client.newCall(Request.Builder().url(url).get().build()).execute()
                val requestId = response.header("x-request-id")
                HealthCheckResult(
                    host = baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                    reachable = response.isSuccessful,
                    httpStatus = response.code,
                    requestId = requestId,
                    error = if (response.isSuccessful) null else "HTTP ${response.code}",
                )
            } catch (e: Exception) {
                HealthCheckResult(
                    host = baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                    reachable = false,
                    httpStatus = null,
                    requestId = null,
                    error = e.message?.take(120),
                )
            }
        }
}

data class HealthCheckResult(
    val host: String,
    val reachable: Boolean,
    val httpStatus: Int?,
    val requestId: String?,
    val error: String?,
)
