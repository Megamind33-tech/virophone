package com.viroreach.voice.webrtc

import android.content.Context
import android.media.AudioManager
import com.viroreach.core.model.CallStateMachineState
import com.viroreach.voice.api.AudioRoute
import com.viroreach.voice.api.CallStatistics
import com.viroreach.voice.api.SipCredentials
import com.viroreach.voice.api.VoiceCallEvent
import com.viroreach.voice.api.VoiceCallEventType
import com.viroreach.voice.api.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
import org.webrtc.RtpSender
import org.webrtc.SessionDescription

/**
 * WebRTC voice-only engine with Phase 1B resilience:
 * adaptive Opus, ICE restart, continuous gathering, and RTCStats metrics.
 */
class WebRtcVoiceEngine(
    context: Context,
) : VoiceEngine {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var priorAudioMode = AudioManager.MODE_NORMAL
    private var audioConfiguredForCall = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _callState = MutableStateFlow(CallStateMachineState.IDLE)
    private val _callEvents = MutableSharedFlow<VoiceCallEvent>()
    private val _liveStatistics = MutableStateFlow<CallStatistics?>(null)
    val liveStatistics: Flow<CallStatistics?> = _liveStatistics.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var audioSender: RtpSender? = null
    private var activeCallId: String? = null
    private var muted = false
    private var speaker = false
    private var audioRoute = AudioRoute.EARPIECE
    private var lastStats: CallStatistics? = null
    private var pendingRemoteSdp: SessionDescription? = null
    private var pendingRemoteSetComplete: (() -> Unit)? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionApplied = false
    private var statsJob: Job? = null
    private var iceRecoveryJob: Job? = null
    private var iceRecoveryAttempts = 0
    private var lastIceServers: List<PeerConnection.IceServer> = emptyList()

    var onLocalIceCandidate: ((IceCandidate) -> Unit)? = null
    var onIceRestartOffer: ((SessionDescription) -> Unit)? = null
    var iceTransportPolicyRelay: Boolean = false

    override suspend fun initialize(): Result<Unit> {
        if (factory != null) return Result.success(Unit)
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        return Result.success(Unit)
    }

    suspend fun ensureInitialized(): Result<Unit> = initialize()

    override suspend fun register(sipUri: String, credentials: SipCredentials): Result<Unit> =
        Result.success(Unit)

    override suspend fun unregister(): Result<Unit> {
        _callState.value = CallStateMachineState.IDLE
        return Result.success(Unit)
    }

    override suspend fun startCall(targetUri: String, sessionMaterial: Map<String, String>): Result<String> {
        val callId = sessionMaterial["callId"] ?: return Result.failure(IllegalArgumentException("callId required"))
        activeCallId = callId
        iceRecoveryAttempts = 0
        _callState.value = CallStateMachineState.CONNECTING
        val iceServers = parseIceServers(sessionMaterial)
        createPeerConnection(iceServers)
        _callState.value = CallStateMachineState.SIGNALING
        return Result.success(callId)
    }

    override suspend fun acceptCall(callId: String): Result<Unit> {
        activeCallId = callId
        iceRecoveryAttempts = 0
        _callState.value = CallStateMachineState.CONNECTING
        return Result.success(Unit)
    }

    fun applySessionMaterial(sessionMaterial: Map<String, String>) {
        val iceServers = parseIceServers(sessionMaterial)
        lastIceServers = iceServers
        if (peerConnection == null) {
            createPeerConnection(iceServers)
        } else {
            updateIceServers(iceServers)
        }
        pendingRemoteSdp?.let { sdp ->
            val cb = pendingRemoteSetComplete ?: {}
            pendingRemoteSdp = null
            pendingRemoteSetComplete = null
            remoteDescriptionApplied = false
            peerConnection?.setRemoteDescription(
                SimpleSdpObserver(onSetComplete = {
                    remoteDescriptionApplied = true
                    flushPendingIceCandidates()
                    cb()
                }),
                sdp,
            )
        }
    }

    fun updateIceServers(sessionMaterial: Map<String, String>) {
        updateIceServers(parseIceServers(sessionMaterial))
    }

    private fun updateIceServers(iceServers: List<PeerConnection.IceServer>) {
        lastIceServers = iceServers
        peerConnection?.setConfiguration(buildRtcConfiguration(iceServers))
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
        if (audioConfiguredForCall) {
            runCatching { audioManager.isSpeakerphoneOn = enabled }
        }
    }

    /**
     * Puts the device audio system into VoIP mode. Without
     * MODE_IN_COMMUNICATION the received remote audio is treated as a media
     * stream and is frequently silent or mis-routed — a common "connected but
     * no talking" symptom on Android.
     */
    private fun configureAudioForCall() {
        if (audioConfiguredForCall) return
        priorAudioMode = audioManager.mode
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = speaker
        }
        audioConfiguredForCall = true
    }

    private fun restoreAudioMode() {
        if (!audioConfiguredForCall) return
        runCatching {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = priorAudioMode
        }
        audioConfiguredForCall = false
    }

    override fun setAudioRoute(route: AudioRoute) {
        audioRoute = route
    }

    override val callState: Flow<CallStateMachineState> = _callState.asStateFlow()
    override val callEvents: Flow<VoiceCallEvent> = _callEvents.asSharedFlow()

    override fun getStatistics(callId: String): CallStatistics? = lastStats

    fun startStatisticsPolling(intervalMs: Long = 2_000L) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                collectStatistics()
                delay(intervalMs)
            }
        }
    }

    fun stopStatisticsPolling() {
        statsJob?.cancel()
        statsJob = null
    }

    fun collectIceSummary(onResult: (String) -> Unit) {
        val pc = peerConnection
        if (pc == null) {
            onResult("NO PEERCONNECTION")
            return
        }
        pc.getStats { report ->
            val stats = report.statsMap.values
            val pair = stats.firstOrNull { s ->
                s.type == "candidate-pair" &&
                    (s.members["nominated"] == true || s.members["state"]?.toString() == "succeeded")
            }
            if (pair == null) {
                onResult("NO NOMINATED PAIR")
                return@getStats
            }
            val localId = pair.members["localCandidateId"]?.toString()
            val remoteId = pair.members["remoteCandidateId"]?.toString()
            val local = stats.firstOrNull { it.id == localId }
            val remote = stats.firstOrNull { it.id == remoteId }
            val localType = local?.members?.get("candidateType") ?: local?.members?.get("type") ?: "?"
            val remoteType = remote?.members?.get("candidateType") ?: remote?.members?.get("type") ?: "?"
            val proto = local?.members?.get("protocol") ?: "?"
            onResult("local=$localType remote=$remoteType proto=$proto")
        }
    }

    /**
     * ICE restart for network transitions or transient disconnects.
     * Emits a new local offer via [onIceRestartOffer] when created.
     */
    fun triggerIceRestart(onOfferCreated: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        pc.restartIce()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(
            SimpleSdpObserver(onSuccess = { rawSdp ->
                val tuned = WebRtcSdpUtils.tuneForAdaptiveVoice(rawSdp)
                pc.setLocalDescription(
                    SimpleSdpObserver(onSetComplete = { onOfferCreated(tuned) }),
                    tuned,
                )
            }),
            constraints,
        )
    }

    override suspend fun shutdown() {
        cleanup()
        factory?.dispose()
        factory = null
    }

    fun setRemoteDescription(sdp: SessionDescription, onComplete: () -> Unit = {}) {
        if (peerConnection == null) {
            pendingRemoteSdp = sdp
            pendingRemoteSetComplete = onComplete
            remoteDescriptionApplied = false
            return
        }
        remoteDescriptionApplied = false
        peerConnection?.setRemoteDescription(
            SimpleSdpObserver(onSetComplete = {
                remoteDescriptionApplied = true
                flushPendingIceCandidates()
                onComplete()
            }),
            sdp,
        )
    }

    fun createAnswer(onCreated: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        pc.createAnswer(
            SimpleSdpObserver(onSuccess = { rawSdp ->
                val tuned = WebRtcSdpUtils.tuneForAdaptiveVoice(rawSdp)
                pc.setLocalDescription(
                    SimpleSdpObserver(onSetComplete = { onCreated(tuned) }),
                    tuned,
                )
            }),
            MediaConstraints(),
        )
    }

    fun createOffer(onCreated: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        pc.createOffer(
            SimpleSdpObserver(onSuccess = { rawSdp ->
                val tuned = WebRtcSdpUtils.tuneForAdaptiveVoice(rawSdp)
                pc.setLocalDescription(
                    SimpleSdpObserver(onSetComplete = { onCreated(tuned) }),
                    tuned,
                )
            }),
            MediaConstraints(),
        )
    }

    fun addIceCandidate(candidate: IceCandidate) {
        val pc = peerConnection
        if (pc == null || !remoteDescriptionApplied) {
            pendingIceCandidates.add(candidate)
            return
        }
        pc.addIceCandidate(candidate)
    }

    private fun flushPendingIceCandidates() {
        val pc = peerConnection ?: return
        pendingIceCandidates.forEach { pc.addIceCandidate(it) }
        pendingIceCandidates.clear()
    }

    private fun collectStatistics() {
        val pc = peerConnection ?: return
        pc.getStats { report ->
            val parsed = WebRtcStatsCollector.parse(report.statsMap.values)
            if (parsed != null) {
                lastStats = parsed
                _liveStatistics.value = parsed
                activeCallId?.let { id ->
                    scope.launch {
                        _callEvents.emit(VoiceCallEvent(id, VoiceCallEventType.QUALITY_UPDATE))
                    }
                }
            }
        }
    }

    private fun scheduleIceRecovery() {
        if (iceRecoveryAttempts >= MAX_ICE_RECOVERY_ATTEMPTS) {
            _callState.value = CallStateMachineState.FAILED
            return
        }
        iceRecoveryJob?.cancel()
        iceRecoveryJob = scope.launch {
            delay(ICE_RECOVERY_DELAY_MS)
            iceRecoveryAttempts++
            _callState.value = CallStateMachineState.RECONNECTING
            val handler = onIceRestartOffer
            if (handler != null) {
                triggerIceRestart(handler)
            } else {
                peerConnection?.restartIce()
            }
        }
    }

    private fun parseIceServers(material: Map<String, String>): List<PeerConnection.IceServer> {
        val provided = material["iceServers"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        // localhost entries are the device itself and only stall ICE gathering
        // on real hardware — drop them.
        val cleaned = provided.filterNot {
            it.contains("localhost") || it.contains("127.0.0.1")
        }

        // Always guarantee a publicly reachable STUN server so reflexive
        // candidates can be gathered even if the server list was empty/relay-only.
        val urls = if (cleaned.none { it.startsWith("stun:") }) {
            cleaned + PUBLIC_STUN
        } else {
            cleaned
        }

        val username = material["iceUsername"]
        val credential = material["iceCredential"]
        return urls.map { raw ->
            val url = raw.trim()
            val builder = PeerConnection.IceServer.builder(url)
            if (!username.isNullOrBlank() &&
                !credential.isNullOrBlank() &&
                (url.startsWith("turn:") || url.startsWith("turns:"))
            ) {
                builder.setUsername(username).setPassword(credential)
            }
            builder.createIceServer()
        }
    }

    private fun buildRtcConfiguration(iceServers: List<PeerConnection.IceServer>): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 2
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            if (iceTransportPolicyRelay) {
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
        }
    }

    private fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val f = factory ?: throw IllegalStateException("WebRTC engine not initialized")
        configureAudioForCall()
        lastIceServers = iceServers
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        audioSender = null
        remoteDescriptionApplied = false
        pendingIceCandidates.clear()
        val rtcConfig = buildRtcConfiguration(iceServers)
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        iceRecoveryAttempts = 0
                        iceRecoveryJob?.cancel()
                        _callState.value = CallStateMachineState.ACTIVE
                        activeCallId?.let { id ->
                            scope.launch {
                                _callEvents.emit(VoiceCallEvent(id, VoiceCallEventType.CONNECTED))
                            }
                        }
                        startStatisticsPolling()
                    }
                    PeerConnection.IceConnectionState.FAILED -> scheduleIceRecovery()
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        _callState.value = CallStateMachineState.RECONNECTING
                        scheduleIceRecovery()
                    }
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                if (!receiving && _callState.value == CallStateMachineState.ACTIVE) {
                    _callState.value = CallStateMachineState.RECONNECTING
                }
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { onLocalIceCandidate?.invoke(it) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                stream?.audioTracks?.forEach { it.setEnabled(true) }
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                // Ensure the inbound remote audio track is playing.
                (receiver?.track() as? AudioTrack)?.setEnabled(true)
            }
        }
        peerConnection = f.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("Failed to create PeerConnection")
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        audioSource = f.createAudioSource(audioConstraints)
        localAudioTrack = try {
            f.createAudioTrack("viro_audio", audioSource)
        } catch (e: Exception) {
            throw IllegalStateException("Microphone unavailable — allow mic permission and try again", e)
        } ?: throw IllegalStateException("Microphone unavailable — allow mic permission and try again")
        localAudioTrack?.setEnabled(!muted)
        audioSender = peerConnection?.addTrack(localAudioTrack)
        configureAdaptiveOpusBitrate(audioSender)
    }

    private fun configureAdaptiveOpusBitrate(sender: RtpSender?) {
        val parameters = sender?.parameters ?: return
        parameters.encodings.forEach { encoding ->
            encoding.maxBitrateBps = 64_000
            encoding.minBitrateBps = 6_000
        }
        sender.parameters = parameters
    }

    private fun cleanup() {
        stopStatisticsPolling()
        iceRecoveryJob?.cancel()
        iceRecoveryAttempts = 0
        // WebRTC's native dispose()/close() can throw (e.g. double-dispose racing
        // against a concurrent ICE-recovery attempt on the same peerConnection) —
        // one throwing must not skip the rest of cleanup or leave call state stuck.
        runCatching { localAudioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        audioSender = null
        pendingIceCandidates.clear()
        remoteDescriptionApplied = false
        pendingRemoteSdp = null
        pendingRemoteSetComplete = null
        activeCallId = null
        lastStats = null
        _liveStatistics.value = null
        restoreAudioMode()
        _callState.value = CallStateMachineState.ENDED
        _callState.value = CallStateMachineState.IDLE
    }

    /**
     * Surfaces an SDP negotiation failure instead of silently stalling the call
     * in "connecting". Marks the call MEDIA_FAILED so the UI can react.
     */
    private fun onSdpError(context: String, error: String?) {
        val detail = error ?: "unknown"
        _callState.value = CallStateMachineState.MEDIA_FAILED
        activeCallId?.let { id ->
            scope.launch {
                _callEvents.emit(VoiceCallEvent(id, VoiceCallEventType.ERROR))
            }
        }
        android.util.Log.e("ViroWebRtc", "SDP error [$context]: $detail")
    }

    private inner class SimpleSdpObserver(
        private val onSuccess: (SessionDescription) -> Unit = {},
        private val onSetComplete: () -> Unit = {},
    ) : org.webrtc.SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            sdp?.let(onSuccess)
        }
        override fun onSetSuccess() = onSetComplete()
        override fun onCreateFailure(error: String?) = onSdpError("create", error)
        override fun onSetFailure(error: String?) = onSdpError("set", error)
    }

    companion object {
        private const val MAX_ICE_RECOVERY_ATTEMPTS = 4
        private const val ICE_RECOVERY_DELAY_MS = 2_000L
        private const val PUBLIC_STUN = "stun:stun.l.google.com:19302"
    }
}
