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


    /**
     * Drops every conversation and message held in memory. MessagesStore lives
     * as long as the process, so without this a second account signing in saw
     * the first account's inbox until the app was force-stopped.
     */
    fun clearAll() {
        _conversations.value = emptyList()
        messagesByConversation.values.forEach { it.value = emptyList() }
        messagesByConversation.clear()
    }
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

    /** Upserts a server conversation into the local inbox cache. */
    fun upsertConversation(conversation: Conversation) {
        val without = _conversations.value.filterNot {
            it.id == conversation.id ||
                (conversation.peerUserId != null && it.peerUserId == conversation.peerUserId)
        }
        _conversations.value = (listOf(conversation) + without)
            .sortedByDescending { it.lastTimestampMs }
        messagesByConversation.getOrPut(conversation.id) { MutableStateFlow(emptyList()) }
    }

    fun replaceConversations(conversations: List<Conversation>) {
        _conversations.value = conversations.sortedByDescending { it.lastTimestampMs }
        conversations.forEach { conv ->
            messagesByConversation.getOrPut(conv.id) { MutableStateFlow(emptyList()) }
        }
    }

    fun replaceMessages(conversationId: String, messages: List<ChatMessage>) {
        messagesByConversation.getOrPut(conversationId) { MutableStateFlow(emptyList()) }.value =
            messages.sortedBy { it.timestampMs }
        val last = messages.maxByOrNull { it.timestampMs }
        if (last != null) {
            _conversations.value = _conversations.value.map { conv ->
                if (conv.id == conversationId) {
                    conv.copy(lastMessage = last.body, lastTimestampMs = last.timestampMs)
                } else conv
            }
        }
    }

    fun sendMessage(conversationId: String, body: String, isOutgoing: Boolean = true) {
        if (body.isBlank()) return
        val now = System.currentTimeMillis()
        val msg = ChatMessage(UUID.randomUUID().toString(), conversationId, body.trim(), isOutgoing, now)
        messagesByConversation.getOrPut(conversationId) { MutableStateFlow(emptyList()) }.value += msg
        _conversations.value = _conversations.value.map { conv ->
            if (conv.id == conversationId) {
                conv.copy(lastMessage = body.trim(), lastTimestampMs = now, unreadCount = 0)
            } else conv
        }.sortedByDescending { it.lastTimestampMs }
    }

    fun receiveMessage(conversationId: String, body: String) {
        sendMessage(conversationId, body, isOutgoing = false)
        _conversations.value = _conversations.value.map { conv ->
            if (conv.id == conversationId) conv.copy(unreadCount = conv.unreadCount + 1) else conv
        }
    }

    fun clearUnread(conversationId: String) {
        _conversations.value = _conversations.value.map { conv ->
            if (conv.id == conversationId) conv.copy(unreadCount = 0) else conv
        }
    }
}
