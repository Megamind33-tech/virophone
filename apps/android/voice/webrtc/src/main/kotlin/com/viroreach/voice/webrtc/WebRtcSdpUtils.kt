package com.viroreach.voice.webrtc

import org.webrtc.SessionDescription

/**
 * SDP helpers for Phase 1B — Opus voice with in-band FEC and adaptive bitrate headroom.
 */
object WebRtcSdpUtils {

    private const val OPUS_PT = "111"
    private const val OPUS_FMTP =
        "maxaveragebitrate=64000;minptime=10;useinbandfec=1;stereo=0;sprop-stereo=0"

    fun tuneForAdaptiveVoice(sdp: SessionDescription): SessionDescription {
        val tuned = tuneForAdaptiveVoice(sdp.description)
        return SessionDescription(sdp.type, tuned)
    }

    fun tuneForAdaptiveVoice(sdp: String): String {
        var result = preferOpusPayload(sdp)
        result = ensureOpusFmtp(result)
        result = ensureAudioBandwidth(result)
        return result
    }

    private fun preferOpusPayload(sdp: String): String {
        val opusLine = "a=rtpmap:$OPUS_PT opus/48000/2"
        if (!sdp.contains(opusLine)) return sdp
        val lines = sdp.lines().toMutableList()
        val mAudioIndex = lines.indexOfFirst { it.startsWith("m=audio") }
        if (mAudioIndex < 0) return sdp
        val parts = lines[mAudioIndex].split(" ").toMutableList()
        if (parts.size <= 3) return sdp
        val payloads = parts.drop(3).toMutableList()
        if (payloads.remove(OPUS_PT)) {
            payloads.add(0, OPUS_PT)
            lines[mAudioIndex] = parts.take(3).joinToString(" ") + " " + payloads.joinToString(" ")
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    private fun ensureOpusFmtp(sdp: String): String {
        val fmtpPrefix = "a=fmtp:$OPUS_PT "
        if (sdp.contains(fmtpPrefix)) {
            return sdp.lines().joinToString("\r\n") { line ->
                if (line.startsWith(fmtpPrefix)) {
                    "$fmtpPrefix$OPUS_FMTP"
                } else {
                    line
                }
            } + "\r\n"
        }
        return insertAfterOpusRtpMap(sdp, "$fmtpPrefix$OPUS_FMTP\r\n")
    }

    private fun ensureAudioBandwidth(sdp: String): String {
        if (sdp.contains("b=AS:")) return sdp
        return insertAfterAudioMediaLine(sdp, "b=AS:64\r\n")
    }

    private fun insertAfterOpusRtpMap(sdp: String, insertion: String): String {
        val lines = sdp.lines().toMutableList()
        val idx = lines.indexOfFirst { it.startsWith("a=rtpmap:$OPUS_PT ") }
        if (idx < 0) return sdp
        lines.add(idx + 1, insertion.trimEnd())
        return lines.joinToString("\r\n") + "\r\n"
    }

    private fun insertAfterAudioMediaLine(sdp: String, insertion: String): String {
        val lines = sdp.lines().toMutableList()
        val idx = lines.indexOfFirst { it.startsWith("m=audio") }
        if (idx < 0) return sdp
        lines.add(idx + 1, insertion.trimEnd())
        return lines.joinToString("\r\n") + "\r\n"
    }
}
