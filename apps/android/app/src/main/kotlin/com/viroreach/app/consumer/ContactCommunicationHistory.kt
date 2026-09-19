package com.viroreach.app.consumer

import com.viroreach.app.consumer.data.CallHistoryStore
import com.viroreach.app.consumer.data.CallLogEntry
import com.viroreach.app.messaging.ChatMessage

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
        messages: List<ChatMessage>,
    ): List<ContactHistoryItem> {
        val calls = if (phoneE164.isNullOrBlank()) emptyList() else callHistoryStore.historyForPhone(phoneE164).map { it.toHistoryItem() }
        val msgs = messages.filter { it.type != "SYSTEM" }.map { m ->
            ContactHistoryItem(
                id = m.id,
                kind = ContactHistoryKind.MESSAGE,
                label = if (m.mine) "Message sent" else "Message received",
                detail = when {
                    m.deleted -> "Deleted message"
                    m.type == "VOICE" -> "🎤 Voice message"
                    m.type == "IMAGE" -> "📷 Photo"
                    m.type == "LOOP" -> "🔁 ${m.body ?: "Loop"}"
                    else -> m.body.orEmpty()
                },
                timestampMs = m.createdAt,
            )
        }
        return (calls + msgs).sortedByDescending { it.timestampMs }
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
