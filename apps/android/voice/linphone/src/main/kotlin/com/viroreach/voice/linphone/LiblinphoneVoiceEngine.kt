package com.viroreach.voice.linphone

import com.viroreach.core.model.CallStateMachineState
import com.viroreach.voice.api.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Liblinphone adapter — Phase 0 stub proving the abstraction boundary.
 * Production integration requires Linphone SDK (AGPL — see DEPENDENCY_LICENSES.md).
 */
class LiblinphoneVoiceEngine : VoiceEngine {
    private val _callState = MutableStateFlow(CallStateMachineState.IDLE)
    private val _callEvents = MutableSharedFlow<VoiceCallEvent>()
    private var initialized = false
    private var muted = false
    private var speaker = false

    override suspend fun initialize(): Result<Unit> {
        // Phase 0: SDK integration feasibility stub
        initialized = true
        return Result.success(Unit)
    }

    override suspend fun register(sipUri: String, credentials: SipCredentials): Result<Unit> {
        if (!initialized) return Result.failure(IllegalStateException("Not initialized"))
        return Result.success(Unit)
    }

    override suspend fun unregister(): Result<Unit> {
        _callState.value = CallStateMachineState.IDLE
        return Result.success(Unit)
    }

    override suspend fun startCall(targetUri: String, sessionMaterial: Map<String, String>): Result<String> {
        _callState.value = CallStateMachineState.CONNECTING
        val callId = sessionMaterial["callId"] ?: "call-${System.currentTimeMillis()}"
        _callState.value = CallStateMachineState.RINGING
        return Result.success(callId)
    }

    override suspend fun acceptCall(callId: String): Result<Unit> {
        _callState.value = CallStateMachineState.ACTIVE
        _callEvents.emit(VoiceCallEvent(callId, VoiceCallEventType.CONNECTED))
        return Result.success(Unit)
    }

    override suspend fun rejectCall(callId: String): Result<Unit> {
        _callState.value = CallStateMachineState.PEER_REJECTED
        _callState.value = CallStateMachineState.ENDED
        return Result.success(Unit)
    }

    override suspend fun endCall(callId: String): Result<Unit> {
        _callState.value = CallStateMachineState.ENDING
        _callEvents.emit(VoiceCallEvent(callId, VoiceCallEventType.DISCONNECTED))
        _callState.value = CallStateMachineState.ENDED
        _callState.value = CallStateMachineState.IDLE
        return Result.success(Unit)
    }

    override fun setMuted(muted: Boolean) { this.muted = muted }
    override fun setSpeaker(enabled: Boolean) { this.speaker = enabled }
    override fun setAudioRoute(route: AudioRoute) { /* SDK integration point */ }

    override val callState: Flow<CallStateMachineState> = _callState.asStateFlow()
    override val callEvents: Flow<VoiceCallEvent> = _callEvents.asSharedFlow()

    override fun getStatistics(callId: String): CallStatistics? {
        return CallStatistics(0f, 0f, 0f, 0f, "opus")
    }

    override suspend fun shutdown() {
        initialized = false
        _callState.value = CallStateMachineState.IDLE
    }
}
