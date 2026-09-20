package com.viroreach.app.messaging

import com.viroreach.core.network.MediaDto

/**
 * What is actually inside an end-to-end encrypted message.
 *
 * Everything that makes a message what it is — its text, the question of a
 * poll, the name of a file and the key that opens it, where someone is —
 * goes in here and is sealed as one thing. The server is left with the
 * shape: that a message exists, who it is for, and that there are bytes
 * behind it.
 */
data class SealedPayload(
    /** The format, so a later one can be recognised rather than misread. */
    val v: Int = 1,
    val type: String,
    val body: String? = null,
    /** The message metadata map: gif, sticker, contact, place, poll, effect. */
    val meta: Map<String, Any?>? = null,
    val media: SealedMediaRef? = null,
)

/** A file, and the key to it. */
data class SealedMediaRef(
    val id: String,
    val key: String,
    val iv: String,
    val mime: String,
    val name: String? = null,
    val durationMs: Long? = null,
    val waveform: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null,
)

/** The file this phone already sealed and uploaded, read back off the row. */
internal fun MediaDto.toSealedRef(): SealedMediaRef? {
    val key = sealedKey ?: return null
    val iv = sealedIv ?: return null
    return SealedMediaRef(
        id = id,
        key = key,
        iv = iv,
        mime = mime ?: "application/octet-stream",
        name = originalName,
        durationMs = durationMs,
        waveform = waveform,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
    )
}

object SealedMessage {
    /** The sealed form of a message, as one string to encrypt. */
    fun pack(payload: SealedPayload): String = ChatJson.gson.toJson(payload)

    /**
     * Reads a sealed message back. Null when it is not one — an older build
     * sealed plain text, and something from the future is not ours to guess at.
     */
    fun unpack(plain: String): SealedPayload? = runCatching {
        val payload = ChatJson.gson.fromJson(plain, SealedPayload::class.java)
        if (payload?.type.isNullOrBlank() || payload.v > VERSION) null else payload
    }.getOrNull()

    /** The file as this phone will handle it, carrying the key that opens it. */
    fun mediaDto(ref: SealedMediaRef, kind: String): MediaDto = MediaDto(
        id = ref.id,
        kind = kind,
        mime = ref.mime,
        sizeBytes = ref.sizeBytes,
        durationMs = ref.durationMs,
        waveform = ref.waveform,
        width = ref.width,
        height = ref.height,
        originalName = ref.name,
        sealedKey = ref.key,
        sealedIv = ref.iv,
    )

    const val VERSION = 1
}
