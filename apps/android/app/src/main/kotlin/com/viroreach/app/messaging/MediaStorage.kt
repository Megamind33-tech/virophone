package com.viroreach.app.messaging

import android.content.Context
import com.viroreach.core.database.MessagingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What Viro is holding on this phone, in the terms a person would ask about. */
data class StorageUsage(
    val photoBytes: Long = 0,
    val voiceBytes: Long = 0,
    val documentBytes: Long = 0,
    val otherBytes: Long = 0,
    /** Files waiting to be sent. Never cleared — they aren't on the server yet. */
    val outgoingBytes: Long = 0,
    val byConversation: List<ConversationUsage> = emptyList(),
) {
    val downloadedBytes: Long get() = photoBytes + voiceBytes + documentBytes + otherBytes
    val totalBytes: Long get() = downloadedBytes + outgoingBytes
}

data class ConversationUsage(val conversationId: String, val title: String?, val bytes: Long, val files: Int)

/**
 * Downloaded chat media can always be fetched again from the server, so
 * clearing it is safe. Anything still waiting to be sent is left alone.
 */
class MediaStorage(private val context: Context, private val media: MediaFiles) {
    private val dao = MessagingDatabase.get(context.applicationContext).dao()

    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val cached = media.cachedFiles()
        val byMediaId = cached.associateBy { it.name.substringBefore('.') }
        var photo = 0L
        var voice = 0L
        var document = 0L
        var other = 0L

        // Which file belongs to which chat, and what kind it is, comes from the
        // messages themselves: the cache is keyed by media id alone.
        val rows = dao.mediaMessages()
        val perConversation = mutableMapOf<String, Pair<Long, Int>>()
        val claimed = mutableSetOf<String>()
        for (row in rows) {
            val mediaId = ChatJson.media(row.mediaJson)?.id?.takeIf { it.isNotBlank() } ?: continue
            val file = byMediaId[mediaId] ?: continue
            if (!claimed.add(mediaId)) continue
            val size = file.length()
            when (row.type) {
                "IMAGE" -> photo += size
                "VOICE" -> voice += size
                "FILE" -> document += size
                else -> other += size
            }
            val prev = perConversation[row.conversationId] ?: (0L to 0)
            perConversation[row.conversationId] = (prev.first + size) to (prev.second + 1)
        }
        // Anything cached that no message claims (a deleted message, say).
        for (file in cached) if (file.name.substringBefore('.') !in claimed) other += file.length()

        val titles = dao.allConversations().associate { it.id to it.title }
        StorageUsage(
            photoBytes = photo,
            voiceBytes = voice,
            documentBytes = document,
            otherBytes = other,
            outgoingBytes = media.outgoingFiles().sumOf { it.length() },
            byConversation = perConversation.entries
                .map { (id, v) -> ConversationUsage(id, titles[id], v.first, v.second) }
                .sortedByDescending { it.bytes },
        )
    }

    /** Frees everything downloaded. Messages stay; their files come back when opened. */
    suspend fun clearDownloads(): Long = withContext(Dispatchers.IO) {
        val freed = media.cachedFiles().sumOf { it.length() }
        media.clearCache()
        freed
    }

    /** Frees what one chat has downloaded. */
    suspend fun clearConversation(conversationId: String): Long = withContext(Dispatchers.IO) {
        val ids = dao.mediaMessages()
            .filter { it.conversationId == conversationId }
            .mapNotNull { ChatJson.media(it.mediaJson)?.id?.takeIf { id -> id.isNotBlank() } }
            .toSet()
        var freed = 0L
        for (file in media.cachedFiles()) {
            if (file.name.substringBefore('.') in ids) {
                freed += file.length()
                file.delete()
            }
        }
        freed
    }
}

/** "1.2 GB", "340 MB", "8.4 MB" — a size as a person would say it. */
fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 1024 -> String.format(java.util.Locale.US, "%.1f GB", mb / 1024)
        mb >= 10 -> String.format(java.util.Locale.US, "%.0f MB", mb)
        mb >= 0.1 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    }
}

/** The share of the total this kind takes, 0..1, for the bar on the screen. */
fun storageShare(part: Long, total: Long): Float = if (total <= 0) 0f else (part.toDouble() / total).toFloat()

