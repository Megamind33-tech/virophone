package com.viroreach.app

import com.viroreach.app.consumer.data.CallLogType
import com.viroreach.app.consumer.data.ServerCallHistoryMapper
import com.viroreach.core.network.CallHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCallHistoryMapperTest {
    @Test
    fun mapsMissedIncomingWhenNeverAnswered() {
        val entry = CallHistoryEntry(
            id = "c1",
            callerUserId = "peer",
            calleeUserId = "me",
            status = "ENDED",
            routeType = "INTERNET_P2P",
            startedAt = "2026-09-16T12:00:00Z",
            answeredAt = null,
            endedAt = "2026-09-16T12:00:30Z",
            peerUserId = "peer",
            peerDisplayName = "Alice",
            direction = "INCOMING",
        )
        val log = ServerCallHistoryMapper.toLogEntry(entry, "me")
        assertEquals(CallLogType.MISSED, log.type)
        assertEquals("Alice", log.name)
    }

    @Test
    fun mapsCompletedOutgoingWithDuration() {
        val entry = CallHistoryEntry(
            id = "c2",
            callerUserId = "me",
            calleeUserId = "peer",
            status = "ENDED",
            routeType = null,
            startedAt = "2026-09-16T12:00:00Z",
            answeredAt = "2026-09-16T12:00:05Z",
            endedAt = "2026-09-16T12:01:05Z",
            peerUserId = "peer",
            peerDisplayName = "Bob",
            direction = "OUTGOING",
        )
        val log = ServerCallHistoryMapper.toLogEntry(entry, "me")
        assertEquals(CallLogType.COMPLETED, log.type)
        assertEquals(60, log.durationSeconds)
        assertTrue(log.answerTimestampMs != null)
    }
}
