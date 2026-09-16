package com.viroreach.voice.webrtc

import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.SessionDescription

class WebRtcSdpUtilsTest {

    @Test
    fun `tunes opus fmtp for adaptive voice`() {
        val raw = """
            v=0
            m=audio 9 UDP/TLS/RTP/SAVPF 111 63
            a=rtpmap:111 opus/48000/2
            a=rtpmap:63 telephone-event/8000
        """.trimIndent().replace("\n", "\r\n") + "\r\n"

        val tuned = WebRtcSdpUtils.tuneForAdaptiveVoice(
            SessionDescription(SessionDescription.Type.OFFER, raw),
        )

        assertTrue(tuned.description.contains("a=fmtp:111 maxaveragebitrate=64000"))
        assertTrue(tuned.description.contains("useinbandfec=1"))
        assertTrue(tuned.description.contains("b=AS:64"))
    }

    @Test
    fun `prefers opus payload on audio m-line`() {
        val raw = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 63 111\r\na=rtpmap:111 opus/48000/2\r\n"
        val tuned = WebRtcSdpUtils.tuneForAdaptiveVoice(raw)
        assertTrue(tuned.startsWith("v=0"))
        assertTrue(tuned.contains("m=audio 9 UDP/TLS/RTP/SAVPF 111 63"))
    }
}
