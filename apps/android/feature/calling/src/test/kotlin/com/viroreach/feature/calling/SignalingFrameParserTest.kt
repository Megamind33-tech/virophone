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
    fun `malformed json returns warning not exception`() {
        val result = SignalingFrameParser.parse("not-json")
        assertNotNull(result.warning)
    }
}
