package com.viroreach.feature.calling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SignalingFrameParserTest {
    @Test
    fun `parses call incoming server push`() {
        val result = SignalingFrameParser.parse(
            """{"type":"call.incoming","callId":"abc","fromUserId":"u1","payload":{"callerPhoneE164":"+260961582985"}}""",
        )
        assertEquals("call.incoming", result.message?.type)
    }

    @Test
    fun `parses valid signaling frame with type`() {
        val result = SignalingFrameParser.parse(
            """{"type":"call.ringing","callId":"abc","fromUserId":"u1"}""",
        )
        assertEquals("call.ringing", result.message?.type)
        assertEquals("abc", result.message?.callId)
    }

    @Test
    fun `server ack without type does not crash`() {
        val result = SignalingFrameParser.parse("""{"delivered":true}""")
        assertNull(result.message)
        assertNull(result.warning)
    }

    @Test
    fun `server error ack surfaces error code`() {
        val result = SignalingFrameParser.parse("""{"error":"call_not_found"}""")
        assertEquals("call_not_found", result.serverAckError)
        assertNull(result.message)
    }

    @Test
    fun `missing type field does not throw No value for type`() {
        val result = SignalingFrameParser.parse("""{"callId":"abc"}""")
        assertNull(result.message)
        assertNull(result.warning)
    }

    @Test
    fun `parses conference peer-joined using roomId and deviceId`() {
        val result = SignalingFrameParser.parse(
            """{"type":"conf.peer-joined","roomId":"room-1","userId":"u2","deviceId":"d2"}""",
        )
        assertEquals("conf.peer-joined", result.message?.type)
        assertEquals("room-1", result.message?.roomId)
        assertEquals("u2", result.message?.fromUserId)
        assertEquals("d2", result.message?.fromDeviceId)
    }

    @Test
    fun `parses conf joined roster as payload participants`() {
        val result = SignalingFrameParser.parse(
            """{"type":"conf.joined","roomId":"r1","participants":[{"userId":"u1","deviceId":"d1"}]}""",
        )
        assertEquals("conf.joined", result.message?.type)
        assertEquals("d1", result.message?.payload?.optJSONArray("participants")?.optJSONObject(0)?.optString("deviceId"))
    }
}
