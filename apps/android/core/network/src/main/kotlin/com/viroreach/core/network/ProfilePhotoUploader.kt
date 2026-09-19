package com.viroreach.core.network

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
     * Decodes the chosen image, scales it down and re-encodes it as JPEG.
     *
     * The original file was uploaded byte-for-byte, which meant every real
     * camera photo failed: a phone shoots 3-8MB and the server accepts 2MB, so
     * "Couldn't upload photo" was the only outcome anyone ever saw. An avatar is
     * displayed at a few hundred pixels, so full sensor resolution buys nothing
     * — and on mobile data in Zambia, uploading 6MB to show a 96dp circle is
     * worth avoiding on its own.
     *
     * inJustDecodeBounds + inSampleSize means the full-size bitmap is never
     * allocated; a large photo would otherwise risk OutOfMemory on a low-end
     * device before it ever reached the network.
     */
    private fun copyUriToTempFile(uri: Uri): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IllegalStateException("Could not read selected photo")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("That file does not look like an image")
        }

        var sample = 1
        while (
            bounds.outWidth / sample > MAX_EDGE_PX * 2 ||
            bounds.outHeight / sample > MAX_EDGE_PX * 2
        ) {
            sample *= 2
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("Could not read selected photo")

        val longest = maxOf(decoded.width, decoded.height)
        val scaled = if (longest > MAX_EDGE_PX) {
            val ratio = MAX_EDGE_PX.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }

        val temp = File.createTempFile("viro_avatar_", ".jpg", context.cacheDir)
        temp.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return temp
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"

    private companion object {
        /** Comfortably above any avatar display size, far below a camera frame. */
        const val MAX_EDGE_PX = 1024
        const val JPEG_QUALITY = 85
    }
}
