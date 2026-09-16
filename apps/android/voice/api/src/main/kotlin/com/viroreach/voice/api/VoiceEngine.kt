package com.viroreach.voice.api

import com.viroreach.core.model.CallStateMachineState
import kotlinx.coroutines.flow.Flow

/**
 * Voice engine abstraction — isolates VoIP SDK from the rest of the application.
 * UI and feature modules must interact only through this interface.
 */
interface VoiceEngine {
    suspend fun initialize(): Result<Unit>
    suspend fun register(sipUri: String, credentials: SipCredentials): Result<Unit>
    suspend fun unregister(): Result<Unit>
    suspend fun startCall(targetUri: String, sessionMaterial: Map<String, String>): Result<String>
    suspend fun acceptCall(callId: String): Result<Unit>
    suspend fun rejectCall(callId: String): Result<Unit>
    suspend fun endCall(callId: String): Result<Unit>
    fun setMuted(muted: Boolean)
    fun setSpeaker(enabled: Boolean)
    fun setAudioRoute(route: AudioRoute)
    val callState: Flow<CallStateMachineState>
    val callEvents: Flow<VoiceCallEvent>
    fun getStatistics(callId: String): CallStatistics?
    suspend fun shutdown()
}

data class SipCredentials(
    val username: String,
    val password: String,
    val domain: String
)

enum class AudioRoute {
    EARPIECE, SPEAKER, BLUETOOTH, WIRED_HEADSET
}

data class VoiceCallEvent(
    val callId: String,
    val type: VoiceCallEventType,
    val timestamp: Long = System.currentTimeMillis()
)

enum class VoiceCallEventType {
    INCOMING, CONNECTED, DISCONNECTED, ERROR, QUALITY_UPDATE
}

data class CallStatistics(
    val latencyMs: Float,
    val jitterMs: Float,
    val packetLossPercent: Float,
    val bitrateKbps: Float,
    val codec: String,
    /** Cumulative outbound RTP bytes for this call session. */
    val bytesSent: Long = 0L,
    /** Cumulative inbound RTP bytes for this call session. */
    val bytesReceived: Long = 0L,
    /** ICE candidate-pair round-trip time (ms). */
    val roundTripMs: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis(),
)
