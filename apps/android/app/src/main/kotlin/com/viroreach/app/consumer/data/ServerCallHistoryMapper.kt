package com.viroreach.app.consumer.data

import com.viroreach.core.network.CallHistoryEntry
import com.viroreach.core.network.ViroApiService
import java.time.Instant

object ServerCallHistoryMapper {
    /**
     * [localNameFor] resolves a peer's Viro user id against the contacts already
     * on this phone. Checked FIRST: the name someone saved in their own address
     * book is the name they expect to see, ahead of whatever display name the
     * peer set on the platform.
     */
    fun toLogEntry(
        entry: CallHistoryEntry,
        currentUserId: String?,
        localNameFor: (String) -> String? = { null },
    ): CallLogEntry {
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
        // Never fall back to a user id. Showing "a5b4413b" where a name belongs
        // is unreadable and unsearchable, and tells the user their contact is a
        // stranger. Profiles are created with an empty display name, so this
        // fallback was the common case rather than the rare one.
        val name = localNameFor(peerUserId)?.takeIf { it.isNotBlank() }
            ?: entry.peerDisplayName?.takeIf { it.isNotBlank() }
            ?: entry.peerPhoneE164?.takeIf { it.isNotBlank() }
            ?: "Unknown caller"
        return CallLogEntry(
            id = entry.id,
            name = name,
            phoneE164 = entry.peerPhoneE164,
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

suspend fun CallHistoryStore.syncFromServer(
    api: ViroApiService,
    currentUserId: String?,
    localNameFor: (String) -> String? = { null },
) {
    val remote = runCatching { api.getCallHistory() }.getOrElse { return }
    mergeServerHistory(
        remote.map { ServerCallHistoryMapper.toLogEntry(it, currentUserId, localNameFor) },
    )
}
