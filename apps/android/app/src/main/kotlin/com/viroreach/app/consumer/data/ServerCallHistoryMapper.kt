package com.viroreach.app.consumer.data

import com.viroreach.core.network.CallHistoryEntry
import com.viroreach.core.network.ViroApiService
import java.time.Instant

object ServerCallHistoryMapper {
    fun toLogEntry(entry: CallHistoryEntry, currentUserId: String?): CallLogEntry {
        val outgoing = when {
            entry.direction.equals("OUTGOING", true) -> true
            entry.direction.equals("INCOMING", true) -> false
            currentUserId != null && entry.callerUserId == currentUserId -> true
            else -> false
        }
        val answered = entry.answeredAt != null
        val ended = entry.endedAt != null
        val type = when {
            entry.status.equals("MISSED", true) -> CallLogType.MISSED
            !answered && ended && !outgoing -> CallLogType.MISSED
            !answered && ended && outgoing -> CallLogType.OUTGOING
            answered || entry.status.equals("ENDED", true) || entry.status.equals("ACTIVE", true) ->
                CallLogType.COMPLETED
            outgoing -> CallLogType.OUTGOING
            else -> CallLogType.INCOMING
        }
        val startMs = parseIso(entry.startedAt) ?: System.currentTimeMillis()
        val answerMs = parseIso(entry.answeredAt)
        val endMs = parseIso(entry.endedAt)
        val duration = when {
            answerMs != null && endMs != null -> ((endMs - answerMs) / 1000).toInt().coerceAtLeast(0)
            else -> 0
        }
        val peerUserId = entry.peerUserId
            ?: if (outgoing) entry.calleeUserId else entry.callerUserId
        val name = entry.peerDisplayName?.takeIf { it.isNotBlank() }
            ?: peerUserId.take(8)
        return CallLogEntry(
            id = entry.id,
            name = name,
            // The server doesn't return a phone number for history entries — call-back
            // and message-back fall back to peerUserId instead (see HomeScreen/ConsumerNav).
            phoneE164 = null,
            type = type,
            timestampMs = startMs,
            durationSeconds = duration,
            answerTimestampMs = answerMs,
            endTimestampMs = endMs,
            peerUserId = peerUserId,
        )
    }

    private fun parseIso(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
    }
}

suspend fun CallHistoryStore.syncFromServer(api: ViroApiService, currentUserId: String?) {
    val remote = runCatching { api.getCallHistory() }.getOrElse { return }
    mergeServerHistory(remote.map { ServerCallHistoryMapper.toLogEntry(it, currentUserId) })
}
