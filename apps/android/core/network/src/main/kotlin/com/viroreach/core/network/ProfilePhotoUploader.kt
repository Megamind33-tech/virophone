package com.viroreach.core.network

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class ProfilePhotoUploader(
    private val context: Context,
    private val sessionTokenManager: SessionTokenManager,
    private val baseUrl: String = BuildConfig.API_BASE_URL,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // Everything below blocks: bitmap decoding and OkHttp's execute(). Callers
    // launch this from a Compose scope, which runs on the main thread, and
    // Android refuses network I/O there with NetworkOnMainThreadException — so
    // until this switched dispatcher, every upload failed before a single byte
    // left the phone, whatever the photo's size.
    suspend fun upload(uri: Uri): MeResponse = withContext(Dispatchers.IO) { uploadBlocking(uri) }

    private suspend fun uploadBlocking(uri: Uri): MeResponse {
        val token = sessionTokenManager.getAccessTokenForRequest()
            ?: throw IllegalStateException("Not authenticated")
        val tempFile = copyUriToTempFile(uri)
        try {
            // Always JPEG: copyUriToTempFile re-encodes, so the source type
            // (HEIC, PNG, WebP) is no longer what is being sent.
            val requestBody = tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", tempFile.name, requestBody)
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addPart(part)
                .build()
            val request = Request.Builder()
                .url("${baseUrl.ensureTrailingSlash()}api/v1/me/avatar")
                .header("Authorization", "Bearer $token")
                .post(multipart)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Avatar upload failed (${response.code}): $body")
                }
                return gson.fromJson(body, MeResponse::class.java)
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * The original file used to be uploaded byte-for-byte, so every real
     * camera photo (3-8MB) was rejected. It is re-encoded small and upright
     * first; see [ImageDownscaler].
     */
    private fun copyUriToTempFile(uri: Uri): File {
        val temp = File.createTempFile("viro_avatar_", ".jpg", context.cacheDir)
        try {
            ImageDownscaler.writeJpeg(context, uri, temp)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        return temp
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"
}
