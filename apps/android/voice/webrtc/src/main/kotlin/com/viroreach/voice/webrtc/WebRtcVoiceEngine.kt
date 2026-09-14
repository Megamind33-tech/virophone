package com.viroreach.voice.webrtc

import android.content.Context
import com.viroreach.core.model.CallStateMachineState
import com.viroreach.voice.api.AudioRoute
import com.viroreach.voice.api.CallStatistics
import com.viroreach.voice.api.SipCredentials
import com.viroreach.voice.api.VoiceCallEvent
import com.viroreach.voice.api.VoiceCallEventType
import com.viroreach.voice.api.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription

/**
 * WebRTC voice-only engine (Phase 0.6 POC).
 * Signaling SDP/ICE exchange is delegated to the app signaling layer.
 */
class WebRtcVoiceEngine(
    context: Context,
) : VoiceEngine {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _callState = MutableStateFlow(CallStateMachineState.IDLE)
    private val _callEvents = MutableSharedFlow<VoiceCallEvent>()
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var activeCallId: String? = null
    private var muted = false
    private var speaker = false
    private var audioRoute = AudioRoute.EARPIECE
    private var lastStats: CallStatistics? = null

    override suspend fun initialize(): Result<Unit> {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        return Result.success(Unit)
    }

    override suspend fun register(sipUri: String, credentials: SipCredentials): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun unregister(): Result<Unit> {
        _callState.value = CallStateMachineState.IDLE
        return Result.success(Unit)
    }

    override suspend fun startCall(targetUri: String, sessionMaterial: Map<String, String>): Result<String> {
        val callId = sessionMaterial["callId"] ?: return Result.failure(IllegalArgumentException("callId required"))
        activeCallId = callId
        _callState.value = CallStateMachineState.CONNECTING
        val iceServers = parseIceServers(sessionMaterial)
        createPeerConnection(iceServers)
        _callState.value = CallStateMachineState.SIGNALING
        return Result.success(callId)
    }

    override suspend fun acceptCall(callId: String): Result<Unit> {
        activeCallId = callId
        _callState.value = CallStateMachineState.CONNECTING
        return Result.success(Unit)
    }

    override suspend fun rejectCall(callId: String): Result<Unit> {
        _callState.value = CallStateMachineState.PEER_REJECTED
        cleanup()
        return Result.success(Unit)
    }

    override suspend fun endCall(callId: String): Result<Unit> {
        _callState.value = CallStateMachineState.ENDING
        _callEvents.emit(VoiceCallEvent(callId, VoiceCallEventType.DISCONNECTED))
        cleanup()
        return Result.success(Unit)
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        localAudioTrack?.setEnabled(!muted)
    }

    override fun setSpeaker(enabled: Boolean) {
        speaker = enabled
        audioRoute = if (enabled) AudioRoute.SPEAKER else AudioRoute.EARPIECE
    }

    override fun setAudioRoute(route: AudioRoute) {
        audioRoute = route
    }

    override val callState: Flow<CallStateMachineState> = _callState.asStateFlow()
    override val callEvents: Flow<VoiceCallEvent> = _callEvents.asSharedFlow()

    override fun getStatistics(callId: String): CallStatistics? = lastStats

    override suspend fun shutdown() {
        cleanup()
        factory?.dispose()
        factory = null
    }

    fun setRemoteDescription(sdp: SessionDescription, onComplete: () -> Unit = {}) {
        peerConnection?.setRemoteDescription(SimpleSdpObserver(onSetComplete = onComplete), sdp)
    }

    fun createAnswer(onCreated: (SessionDescription) -> Unit) {
        peerConnection?.createAnswer(SimpleSdpObserver(onSuccess = onCreated), MediaConstraints())
    }

    fun createOffer(onCreated: (SessionDescription) -> Unit) {
        peerConnection?.createOffer(SimpleSdpObserver(onSuccess = onCreated), MediaConstraints())
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    private fun parseIceServers(material: Map<String, String>): List<PeerConnection.IceServer> {
        val urls = material["iceServers"]?.split(',')?.filter { it.isNotBlank() }
            ?: listOf("stun:stun.l.google.com:19302")
        return urls.map { PeerConnection.IceServer.builder(it.trim()).createIceServer() }
    }

    private fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        _callState.value = CallStateMachineState.ACTIVE
                        activeCallId?.let { id ->
                            scope.launch {
                                _callEvents.emit(VoiceCallEvent(id, VoiceCallEventType.CONNECTED))
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED -> _callState.value = CallStateMachineState.FAILED
                    PeerConnection.IceConnectionState.DISCONNECTED -> _callState.value = CallStateMachineState.RECONNECTING
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }
        peerConnection = factory?.createPeerConnection(rtcConfig, observer)
        val audioConstraints = MediaConstraints()
        audioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack = factory?.createAudioTrack("viro_audio", audioSource)
        localAudioTrack?.setEnabled(!muted)
        peerConnection?.addTrack(localAudioTrack)
    }

    private fun cleanup() {
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        activeCallId = null
        _callState.value = CallStateMachineState.ENDED
        _callState.value = CallStateMachineState.IDLE
    }

    private class SimpleSdpObserver(
        private val onSuccess: (SessionDescription) -> Unit = {},
        private val onSetComplete: () -> Unit = {},
    ) : org.webrtc.SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            sdp?.let(onSuccess)
        }
        override fun onSetSuccess() = onSetComplete()
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
