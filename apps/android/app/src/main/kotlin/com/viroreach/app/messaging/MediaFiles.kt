package com.viroreach.app.messaging

import android.content.Context
import com.viroreach.core.network.MediaDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Voice notes and photos: uploads, and a private on-phone cache of downloads.
 *
 * Media is never public — the server serves it only to people in the
 * conversation — so every request goes through the authenticated client.
 * A download is kept, so a voice note plays again without spending data.
 */
class MediaFiles(
    context: Context,
    private val http: OkHttpClient,
    private val baseUrl: String,
) {
    private val dir = File(context.filesDir, "chat_media").apply { mkdirs() }
    private val outgoing = File(context.filesDir, "chat_outgoing").apply { mkdirs() }

    fun newOutgoingFile(extension: String): File =
        File(outgoing, "${System.currentTimeMillis()}_${(1000..9999).random()}.$extension")

    fun urlFor(mediaId: String): String = "${baseUrl}api/v1/messages/media/$mediaId"

    suspend fun upload(
        file: File,
        mime: String,
        durationMs: Long? = null,
        waveform: String? = null,
        width: Int? = null,
        height: Int? = null,
        /** "FILE" for a document — it keeps [fileName] and allows document types. */
        kind: String? = null,
        fileName: String? = null,
    ): MediaDto = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaTypeOrNull()))
            .apply {
                durationMs?.let { addFormDataPart("durationMs", it.toString()) }
                waveform?.let { addFormDataPart("waveform", it) }
                width?.let { addFormDataPart("width", it.toString()) }
                height?.let { addFormDataPart("height", it.toString()) }
                kind?.let { addFormDataPart("kind", it) }
                fileName?.let { addFormDataPart("fileName", it) }
            }
            .build()
        val request = Request.Builder().url("${baseUrl}api/v1/messages/media").post(body).build()
        http.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("Upload failed (${res.code}): $text")
            ChatJson.gson.fromJson(text, MediaDto::class.java)
        }
    }

    /** The downloaded copy, if already on the phone. Never touches the network. */
    fun cachedFile(media: MediaDto): File? =
        dir.listFiles { f -> f.name.startsWith("${media.id}.") && !f.name.endsWith(".part") }?.firstOrNull()

    /** The local copy of [media], downloading it first if needed. Null if unavailable. */
    suspend fun fetch(media: MediaDto): File? = withContext(Dispatchers.IO) {
        if (media.id.isBlank()) return@withContext null
        val ext = when {
            // A document keeps its own extension, so other apps can open it.
            media.kind == "FILE" -> media.originalName?.substringAfterLast('.', "")
                ?.takeIf { it.isNotBlank() && it.length <= 8 && it.all { c -> c.isLetterOrDigit() } }
                ?.lowercase() ?: "bin"
            media.mime?.contains("ogg") == true -> "ogg"
            media.mime?.startsWith("audio/") == true -> "m4a"
            media.mime?.contains("png") == true -> "png"
            media.mime?.contains("webp") == true -> "webp"
            else -> "jpg"
        }
        val file = File(dir, "${media.id}.$ext")
        if (file.exists() && file.length() > 0) return@withContext file
        val request = Request.Builder().url(urlFor(media.id)).get().build()
        runCatching {
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val tmp = File(dir, "${media.id}.part")
                res.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
                file
            }
        }.getOrNull()
    }

    fun wipe() {
        dir.listFiles()?.forEach { it.delete() }
        outgoing.listFiles()?.forEach { it.delete() }
    }
}
