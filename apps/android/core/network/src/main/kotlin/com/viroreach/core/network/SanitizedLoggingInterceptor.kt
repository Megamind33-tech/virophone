package com.viroreach.core.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * Debug-only HTTP logging with secret redaction.
 */
class SanitizedLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val method = request.method
        val path = request.url.encodedPath
        Log.d(TAG, "--> $method $path")
        val response = chain.proceed(request)
        val requestId = response.header("x-request-id")
        if (!response.isSuccessful) {
            val peek = response.peekBody(512).string()
            Log.w(
                TAG,
                "<-- HTTP ${response.code} $method $path" +
                    (requestId?.let { " req=$it" } ?: "") +
                    " body=${redact(peek.take(300))}",
            )
        } else {
            Log.d(TAG, "<-- HTTP ${response.code} $method $path" + (requestId?.let { " req=$it" } ?: ""))
        }
        return response
    }

    private fun redact(body: String): String =
        body
            .replace(Regex(""""(accessToken|refreshToken|trustToken|credential|code)"\s*:\s*"[^"]*""""), """"$1":"[REDACTED]"""")
            .replace(Regex("""Bearer\s+\S+"""), "Bearer [REDACTED]")

    companion object {
        private const val TAG = "ViroHttp"
    }
}
