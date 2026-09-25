package com.viroreach.app.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.viroreach.app.MainActivity
import com.viroreach.app.R
import com.viroreach.app.relationships.ReminderNotifications
import com.viroreach.core.database.ConversationEntity

/**
 * A message arriving while its chat is not on screen. One notification per
 * conversation, replaced as more arrive. Muted chats stay silent; locked
 * chats and private sessions never show what was said.
 */
class MessageNotifier(private val context: Context) {
    private val lines = mutableMapOf<String, MutableList<String>>()
    /** Messages already in a notification: shown once, however often they arrive. */
    private val shown = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages from people you talk to"
            },
        )
    }

    fun show(conversation: ConversationEntity?, senderName: String, message: ChatMessage) {
        if (!ReminderNotifications.canPost(context)) return
        if ((conversation?.mutedUntil ?: 0L) > System.currentTimeMillis()) return
        synchronized(shown) {
            if (shown.containsKey(message.id)) return
            shown[message.id] = true
        }
        ensureChannel()
        val secret = conversation?.locked == true || conversation?.kind == "PRIVATE" || message.viewOnce
        val text = when {
            secret -> if (conversation?.kind == "PRIVATE") "Private message" else "New message"
            message.type == "VOICE" -> "🎤 Voice message"
            message.type == "IMAGE" -> if (message.body.isNullOrBlank()) "📷 Photo" else "📷 ${message.body}"
            message.type == "LOOP" -> "🔁 ${message.body ?: "Loop"}"
            message.type == "FILE" -> "📎 ${message.fileName ?: "Document"}"
            message.type == "CONTACT" -> "👤 ${message.contactCard?.name ?: "Contact"}"
            else -> message.body.orEmpty()
        }
        val key = message.conversationId
        val list = lines.getOrPut(key) { mutableListOf() }
        list.add(text)
        while (list.size > 6) list.removeAt(0)
        val style = NotificationCompat.InboxStyle()
        list.forEach { style.addLine(it) }
        if (list.size > 1) style.setSummaryText("${list.size} new messages")
        val intent = ReminderNotifications.openIntent(
            context,
            MainActivity.OPEN_CHAT,
            // A group has no single peer: open the group itself, not a DM with the sender.
            if (conversation?.kind == "GROUP") null else conversation?.peerUserId ?: message.senderUserId,
            key.hashCode(),
            conversationId = if (conversation?.kind == "GROUP") conversation.id else null,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_viro)
            .setContentTitle(if (conversation?.locked == true) "Viro" else senderName)
            .setContentText(text)
            .setStyle(if (list.size > 1) style else NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setColor(0xFF1565F5.toInt())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(key.hashCode(), n) }
    }

    fun clear(conversationId: String) {
        lines.remove(conversationId)
        runCatching { NotificationManagerCompat.from(context).cancel(conversationId.hashCode()) }
    }

    companion object {
        const val CHANNEL = "viro_messages"
    }
}
