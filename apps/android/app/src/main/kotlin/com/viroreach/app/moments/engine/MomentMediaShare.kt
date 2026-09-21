package com.viroreach.app.moments.engine

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.viroreach.app.moments.MomentRoomState
import com.viroreach.core.network.MomentMediaDto
import com.viroreach.core.network.ViroMomentsApi
import com.viroreach.core.network.momentHttpStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** A file picked on this phone, before it is shared. */
data class PickedMedia(val uri: Uri, val name: String, val mime: String, val sizeBytes: Long, val durationMs: Long?)

/** How a share is going, for the room to show. */
data class ShareProgress(val title: String, val fraction: Float, val error: String? = null)

object MediaRules {
    val VIDEO = setOf("video/mp4", "video/webm", "video/3gpp", "video/quicktime")
    val AUDIO = setOf(
        "audio/mpeg", "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/ogg", "audio/opus",
        "audio/webm", "audio/flac", "audio/wav", "audio/x-wav",
    )
    const val MAX_VIDEO_BYTES = 100L * 1024 * 1024
    const val MAX_AUDIO_BYTES = 30L * 1024 * 1024

    /** Said before anything is uploaded, so nobody spends data on a file that will be refused. */
    fun problemWith(p: PickedMedia): String? = when {
        p.mime in VIDEO && p.sizeBytes > MAX_VIDEO_BYTES -> "That video is too large to share (100 MB at most)."
        p.mime in AUDIO && p.sizeBytes > MAX_AUDIO_BYTES -> "That song is too large to share (30 MB at most)."
        p.mime !in VIDEO && p.mime !in AUDIO -> "Only videos and music can be shared in a Moment."
        p.sizeBytes == 0L -> "That file is empty."
        else -> null
    }
}

/**
 * Sharing something from this phone into the room.
 *
 * The file is streamed from where it is, never copied or held in memory
 * whole, and progress is reported as it goes. Once it lands it can be played
 * straight away for everyone.
 */
class MomentMediaShare(
    private val context: Context,
    private val api: ViroMomentsApi,
    private val room: MomentRoomState,
    val playback: SharedPlayback,
    private val scope: CoroutineScope,
) {
    val progress = MutableStateFlow<ShareProgress?>(null)
    private var job: Job? = null

    suspend fun inspect(uri: Uri): PickedMedia? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "Shared"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val mime = resolver.getType(uri) ?: return@withContext null
        val duration = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally { release() }
            }
        }.getOrNull()
        PickedMedia(uri, name, mime, size, duration)
    }

    /** Shares [uri]; with [thenPlay] it is loaded for everyone as soon as it arrives. */
    fun share(uri: Uri, thenPlay: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            val picked = inspect(uri)
            if (picked == null) {
                progress.value = ShareProgress("", 0f, "That file couldn't be read.")
                return@launch
            }
            val title = picked.name.substringBeforeLast('.').take(120).ifBlank { "Shared" }
            MediaRules.problemWith(picked)?.let {
                progress.value = ShareProgress(title, 0f, it)
                return@launch
            }
            progress.value = ShareProgress(title, 0f)
            try {
                val body = StreamedFile(context.contentResolver, picked.uri, picked.mime.toMediaTypeOrNull(), picked.sizeBytes) { sent ->
                    progress.value = ShareProgress(title, if (picked.sizeBytes > 0) sent.toFloat() / picked.sizeBytes else 0f)
                }
                val part = MultipartBody.Part.createFormData("file", picked.name, body)
                val text = "text/plain".toMediaTypeOrNull()
                val item: MomentMediaDto = api.shareMedia(
                    room.momentId, part,
                    title.toRequestBody(text),
                    picked.durationMs?.toString()?.toRequestBody(text),
                )
                room.added(item)
                progress.value = null
                if (thenPlay) playback.load(item.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.value = ShareProgress(title, 0f, when (momentHttpStatus(e)) {
                    400 -> "That file can't be shared here."
                    403, 404 -> "You're no longer in this room."
                    else -> "Couldn't share it. Check your connection and try again."
                })
            }
        }
    }

    fun dismissProblem() {
        if (progress.value?.error != null) progress.value = null
    }
}

/** A file sent straight from the phone's storage, counting as it goes. */
private class StreamedFile(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val type: MediaType?,
    private val length: Long,
    private val onSent: (Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = type
    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri) ?: throw java.io.IOException("File unavailable")
        input.use {
            val buffer = ByteArray(64 * 1024)
            var sent = 0L
            var lastReport = 0L
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                sent += read
                if (sent - lastReport >= 256 * 1024 || sent == length) { onSent(sent); lastReport = sent }
            }
        }
    }
}
