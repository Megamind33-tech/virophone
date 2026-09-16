package com.viroreach.app.consumer

import com.viroreach.app.consumer.data.CallHistoryStore
import com.viroreach.app.consumer.data.CallLogEntry
import com.viroreach.app.consumer.data.MessagesStore
import com.viroreach.app.consumer.callLogStatusLabel

enum class ContactHistoryKind { CALL, MESSAGE }

data class ContactHistoryItem(
    val id: String,
    val kind: ContactHistoryKind,
    val label: String,
    val detail: String,
    val timestampMs: Long,
)

object ContactCommunicationHistory {
    fun build(
        phoneE164: String?,
        callHistoryStore: CallHistoryStore,
        messagesStore: MessagesStore,
    ): List<ContactHistoryItem> {
        if (phoneE164.isNullOrBlank()) return emptyList()
        val calls = callHistoryStore.historyForPhone(phoneE164).map { entry ->
            entry.toHistoryItem()
        }
        val messages = messagesStore.conversations.value
            .filter { it.phoneE164 == phoneE164 }
            .flatMap { conversation ->
                messagesStore.conversationMessages(conversation.id).value.map { message ->
                    ContactHistoryItem(
                        id = message.id,
                        kind = ContactHistoryKind.MESSAGE,
                        label = if (message.isOutgoing) "Message sent" else "Message received",
                        detail = message.body,
                        timestampMs = message.timestampMs,
                    )
                }
            }
        return (calls + messages).sortedByDescending { it.timestampMs }
    }

    private fun CallLogEntry.toHistoryItem() = ContactHistoryItem(
        id = id,
        kind = ContactHistoryKind.CALL,
        label = callLogStatusLabel(type),
        detail = if (durationSeconds > 0) {
            "${durationSeconds / 60}:${"%02d".format(durationSeconds % 60)}"
        } else {
            "—"
        },
        timestampMs = timestampMs,
    )
}
