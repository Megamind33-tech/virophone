package com.viroreach.app.consumer.data

import com.viroreach.core.network.MessageDto
import com.viroreach.core.network.SendMessageBody
import com.viroreach.core.network.ViroApiService
import java.util.UUID

/**
 * Server-backed messaging: persists and syncs conversations/messages through
 * the Viro API (survives process death and reaches other devices), replacing
 * the in-memory [MessagesStore]. Realtime inbound messages arrive over the
 * signaling socket as `message.new` frames and are merged by the caller.
 */
class ServerMessagesRepository(
    private val api: ViroApiService,
) {
    suspend fun listConversations(currentUserId: String?): List<Conversation> =
        api.listConversations().map { summary ->
            val peer = summary.participants.firstOrNull { it != currentUserId }
            Conversation(
                id = summary.id,
                peerName = summary.title ?: peer?.take(8) ?: "Conversation",
                peerUserId = peer,
                phoneE164 = null,
                lastMessage = summary.lastMessage?.body.orEmpty(),
                lastTimestampMs = parseIso(summary.lastMessage?.createdAt),
                unreadCount = summary.unread,
            )
        }

    suspend fun history(conversationId: String, currentUserId: String?): List<ChatMessage> =
        api.conversationHistory(conversationId).map { it.toChatMessage(currentUserId) }

    /** Sends a message; returns the resolved conversation id from the server. */
    suspend fun send(
        toUserId: String?,
        conversationId: String?,
        body: String,
        clientMsgId: String = UUID.randomUUID().toString(),
    ): String {
        val res = api.sendMessage(
            SendMessageBody(
                toUserId = toUserId,
                conversationId = conversationId,
                body = body,
                clientMsgId = clientMsgId,
            ),
        )
        return res.conversationId
    }

    suspend fun markRead(conversationId: String) {
        api.markConversationRead(conversationId)
    }

    private fun MessageDto.toChatMessage(currentUserId: String?): ChatMessage =
        ChatMessage(
            id = id,
            conversationId = conversationId,
            body = body.orEmpty(),
            isOutgoing = senderUserId == currentUserId,
            timestampMs = parseIso(createdAt),
        )

    private fun parseIso(iso: String?): Long {
        if (iso.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching {
            java.time.Instant.parse(iso).toEpochMilli()
        }.getOrDefault(System.currentTimeMillis())
    }
}
