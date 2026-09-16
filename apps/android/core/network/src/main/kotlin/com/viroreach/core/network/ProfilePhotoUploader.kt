package com.viroreach.core.network

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.google.gson.Gson
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

    suspend fun upload(uri: Uri): MeResponse {
        val token = sessionTokenManager.getAccessTokenForRequest()
            ?: throw IllegalStateException("Not authenticated")
        val tempFile = copyUriToTempFile(uri)
        try {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val requestBody = tempFile.asRequestBody(mime.toMediaTypeOrNull())
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

    private fun copyUriToTempFile(uri: Uri): File {
        val ext = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(context.contentResolver.getType(uri) ?: "image/jpeg")
            ?: "jpg"
        val temp = File.createTempFile("viro_avatar_", ".$ext", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not read selected photo")
        return temp
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"
}
