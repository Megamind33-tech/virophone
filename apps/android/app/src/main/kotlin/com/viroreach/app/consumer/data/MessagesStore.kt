package com.viroreach.app.consumer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class Conversation(
    val id: String,
    val peerName: String,
    val peerUserId: String?,
    val phoneE164: String?,
    val lastMessage: String,
    val lastTimestampMs: Long,
    val unreadCount: Int = 0,
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val body: String,
    val isOutgoing: Boolean,
    val timestampMs: Long,
)

class MessagesStore {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val messagesByConversation = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()

    fun conversationMessages(conversationId: String): StateFlow<List<ChatMessage>> =
        messagesByConversation.getOrPut(conversationId) {
            MutableStateFlow(emptyList())
        }.asStateFlow()

    fun openOrCreateConversation(peerName: String, peerUserId: String?, phoneE164: String?): String {
        val existing = _conversations.value.find {
            (peerUserId != null && it.peerUserId == peerUserId) ||
                (phoneE164 != null && it.phoneE164 == phoneE164)
        }
        if (existing != null) return existing.id
        val id = UUID.randomUUID().toString()
        _conversations.value = _conversations.value + Conversation(
            id = id,
            peerName = peerName,
            peerUserId = peerUserId,
            phoneE164 = phoneE164,
            lastMessage = "",
            lastTimestampMs = System.currentTimeMillis(),
        )
        messagesByConversation[id] = MutableStateFlow(emptyList())
        return id
    }

    fun sendMessage(conversationId: String, body: String, isOutgoing: Boolean = true) {
        if (body.isBlank()) return
        val now = System.currentTimeMillis()
        val msg = ChatMessage(UUID.randomUUID().toString(), conversationId, body.trim(), isOutgoing, now)
        messagesByConversation.getOrPut(conversationId) { MutableStateFlow(emptyList()) }.value += msg
        _conversations.value = _conversations.value.map { conv ->
            if (conv.id == conversationId) {
                conv.copy(lastMessage = body.trim(), lastTimestampMs = now)
            } else conv
        }
    }

    fun receiveMessage(conversationId: String, body: String) {
        sendMessage(conversationId, body, isOutgoing = false)
    }
}
