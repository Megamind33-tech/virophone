package com.viroreach.voice.webrtc

import com.viroreach.voice.api.CallStatistics
import org.webrtc.RTCStats

/**
 * Parses WebRTC [RTCStatsReport] into app-level [CallStatistics].
 */
object WebRtcStatsCollector {

    fun parse(stats: Collection<RTCStats>): CallStatistics? {
        if (stats.isEmpty()) return null

        var inboundJitter = 0.0
        var packetsLost = 0L
        var packetsReceived = 0L
        var bytesReceived = 0L
        var bytesSent = 0L
        var outboundBitrate = 0.0
        var roundTripMs = 0.0
        var codec = "opus"

        stats.forEach { entry ->
            when (entry.type) {
                "inbound-rtp" -> {
                    if (entry.members["kind"]?.toString() != "audio") return@forEach
                    inboundJitter = (entry.members["jitter"] as? Number)?.toDouble() ?: inboundJitter
                    packetsLost = (entry.members["packetsLost"] as? Number)?.toLong() ?: packetsLost
                    packetsReceived = (entry.members["packetsReceived"] as? Number)?.toLong() ?: packetsReceived
                    bytesReceived = (entry.members["bytesReceived"] as? Number)?.toLong() ?: bytesReceived
                    resolveCodec(stats, entry.members["codecId"]?.toString())?.let { codec = it }
                }
                "outbound-rtp" -> {
                    if (entry.members["kind"]?.toString() != "audio") return@forEach
                    bytesSent = (entry.members["bytesSent"] as? Number)?.toLong() ?: bytesSent
                    outboundBitrate = (entry.members["targetBitrate"] as? Number)?.toDouble()
                        ?: (entry.members["bitrate"] as? Number)?.toDouble()
                        ?: outboundBitrate
                    resolveCodec(stats, entry.members["codecId"]?.toString())?.let { codec = it }
                }
                "candidate-pair" -> {
                    val nominated = entry.members["nominated"] == true
                    val state = entry.members["state"]?.toString()
                    if (!nominated && state != "succeeded") return@forEach
                    roundTripMs = (entry.members["currentRoundTripTime"] as? Number)?.toDouble()
                        ?.times(1000.0) ?: roundTripMs
                }
            }
        }

        val lossDenom = packetsLost + packetsReceived
        val packetLossPercent = if (lossDenom > 0) {
            (packetsLost.toFloat() / lossDenom.toFloat()) * 100f
        } else {
            0f
        }

        val jitterMs = (inboundJitter * 1000.0).toFloat().coerceAtLeast(0f)
        val bitrateKbps = when {
            outboundBitrate > 0 -> (outboundBitrate / 1000.0).toFloat()
            bytesSent > 0 -> 0f // caller may derive from delta externally
            else -> 0f
        }

        return CallStatistics(
            latencyMs = roundTripMs.toFloat(),
            jitterMs = jitterMs,
            packetLossPercent = packetLossPercent,
            bitrateKbps = bitrateKbps,
            codec = codec,
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            roundTripMs = roundTripMs.toFloat(),
        )
    }

    private fun resolveCodec(stats: Collection<RTCStats>, codecId: String?): String? {
        if (codecId.isNullOrBlank()) return null
        val codecStat = stats.firstOrNull { it.id == codecId } ?: return null
        return codecStat.members["mimeType"]?.toString()?.substringAfter("/")?.lowercase()
    }
}
