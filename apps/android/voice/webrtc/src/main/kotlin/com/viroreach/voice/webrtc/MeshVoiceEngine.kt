package com.viroreach.voice.webrtc

import android.content.Context
import android.media.AudioManager
import android.util.Log
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
 * Small-N mesh voice: one PeerConnection per remote device, sharing a single
 * local microphone track. The server only relays signaling; media is P2P/TURN.
 */
class MeshVoiceEngine(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private val peers = linkedMapOf<String, MeshPeer>()
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private var muted = false
    private var speaker = true
    private var audioConfigured = false
    private var priorAudioMode = AudioManager.MODE_NORMAL

    var onLocalIceCandidate: ((
        remoteDeviceId: String,
        sdpMid: String,
        sdpMLineIndex: Int,
        candidate: String,
    ) -> Unit)? = null
    var onPeerConnected: ((remoteDeviceId: String) -> Unit)? = null
    var onPeerDisconnected: ((remoteDeviceId: String) -> Unit)? = null

    @Synchronized
    fun initialize() {
        if (factory != null) return
        runCatching {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .createInitializationOptions(),
            )
        }
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val f = factory ?: return
        audioSource = f.createAudioSource(constraints)
        localAudioTrack = f.createAudioTrack("viro_mesh_audio", audioSource)
        localAudioTrack?.setEnabled(!muted)
    }

    fun applyIceServers(material: Map<String, String>) {
        iceServers = parseIceServers(material)
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        localAudioTrack?.setEnabled(!muted)
    }

    fun setSpeaker(enabled: Boolean) {
        speaker = enabled
        if (audioConfigured) {
            runCatching { audioManager.isSpeakerphoneOn = enabled }
        }
    }

    @Synchronized
    fun ensurePeer(remoteDeviceId: String): MeshPeer {
        initialize()
        configureAudio()
        peers[remoteDeviceId]?.let { return it }
        val f = factory ?: throw IllegalStateException("Mesh WebRTC not initialized")
        val peer = MeshPeer(remoteDeviceId)
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED ->
                        onPeerConnected?.invoke(remoteDeviceId)
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.CLOSED ->
                        onPeerDisconnected?.invoke(remoteDeviceId)
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    onLocalIceCandidate?.invoke(
                        remoteDeviceId,
                        it.sdpMid ?: "",
                        it.sdpMLineIndex,
                        it.sdp,
                    )
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                stream?.audioTracks?.forEach { it.setEnabled(true) }
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? AudioTrack)?.setEnabled(true)
            }
        }
        val pc = f.createPeerConnection(rtcConfig(), observer)
            ?: throw IllegalStateException("Failed to create mesh PeerConnection")
        val sender = localAudioTrack?.let { pc.addTrack(it) }
        configureOpus(sender)
        peer.pc = pc
        peers[remoteDeviceId] = peer
        return peer
    }

    fun createOffer(remoteDeviceId: String, onCreated: (sdp: String, type: String) -> Unit) {
        val peer = ensurePeer(remoteDeviceId)
        val pc = peer.pc ?: return
        pc.createOffer(
            SdpObserver(onSuccess = { raw ->
                val tuned = WebRtcSdpUtils.tuneForAdaptiveVoice(raw)
                pc.setLocalDescription(
                    SdpObserver(onSetComplete = { onCreated(tuned.description, tuned.type.canonicalForm()) }),
                    tuned,
                )
            }),
            MediaConstraints(),
        )
    }

    fun createAnswer(remoteDeviceId: String, onCreated: (sdp: String, type: String) -> Unit) {
        val peer = peers[remoteDeviceId] ?: return
        val pc = peer.pc ?: return
        pc.createAnswer(
            SdpObserver(onSuccess = { raw ->
                val tuned = WebRtcSdpUtils.tuneForAdaptiveVoice(raw)
                pc.setLocalDescription(
                    SdpObserver(onSetComplete = { onCreated(tuned.description, tuned.type.canonicalForm()) }),
                    tuned,
                )
            }),
            MediaConstraints(),
        )
    }

    fun setRemoteSdp(
        remoteDeviceId: String,
        type: String,
        sdp: String,
        onComplete: () -> Unit = {},
    ) {
        val sdpType = if (type.equals("answer", ignoreCase = true)) {
            SessionDescription.Type.ANSWER
        } else {
            SessionDescription.Type.OFFER
        }
        setRemoteDescription(remoteDeviceId, SessionDescription(sdpType, sdp), onComplete)
    }

    fun addRemoteIce(
        remoteDeviceId: String,
        sdpMid: String,
        sdpMLineIndex: Int,
        candidate: String,
    ) {
        addIceCandidate(remoteDeviceId, IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun setRemoteDescription(
        remoteDeviceId: String,
        sdp: SessionDescription,
        onComplete: () -> Unit = {},
    ) {
        val peer = ensurePeer(remoteDeviceId)
        val pc = peer.pc ?: return
        pc.setRemoteDescription(
            SdpObserver(onSetComplete = {
                peer.remoteApplied = true
                peer.pendingIce.forEach { pc.addIceCandidate(it) }
                peer.pendingIce.clear()
                onComplete()
            }),
            sdp,
        )
    }

    fun addIceCandidate(remoteDeviceId: String, candidate: IceCandidate) {
        val peer = peers[remoteDeviceId] ?: run {
            val created = ensurePeer(remoteDeviceId)
            created.pendingIce.add(candidate)
            return
        }
        val pc = peer.pc
        if (pc == null || !peer.remoteApplied) {
            peer.pendingIce.add(candidate)
            return
        }
        pc.addIceCandidate(candidate)
    }

    @Synchronized
    fun removePeer(remoteDeviceId: String) {
        val peer = peers.remove(remoteDeviceId) ?: return
        runCatching {
            peer.pc?.close()
            peer.pc?.dispose()
        }
    }

    @Synchronized
    fun shutdown() {
        peers.keys.toList().forEach { removePeer(it) }
        runCatching { localAudioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        localAudioTrack = null
        audioSource = null
        runCatching { factory?.dispose() }
        factory = null
        restoreAudio()
    }

    private fun configureAudio() {
        if (audioConfigured) return
        priorAudioMode = audioManager.mode
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = speaker
        }
        audioConfigured = true
    }

    private fun restoreAudio() {
        if (!audioConfigured) return
        runCatching {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = priorAudioMode
        }
        audioConfigured = false
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
    }

    private fun configureOpus(sender: RtpSender?) {
        val parameters = sender?.parameters ?: return
        parameters.encodings.forEach { encoding ->
            encoding.maxBitrateBps = 64_000
            encoding.minBitrateBps = 6_000
        }
        sender.parameters = parameters
    }

    private fun parseIceServers(material: Map<String, String>): List<PeerConnection.IceServer> {
        val provided = material["iceServers"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val cleaned = provided.filterNot {
            it.contains("localhost") || it.contains("127.0.0.1")
        }
        val urls = if (cleaned.none { it.startsWith("stun:") }) {
            cleaned + "stun:stun.l.google.com:19302"
        } else {
            cleaned
        }
        val username = material["iceUsername"]
        val credential = material["iceCredential"]
        return urls.map { raw ->
            val builder = PeerConnection.IceServer.builder(raw.trim())
            if (!username.isNullOrBlank() &&
                !credential.isNullOrBlank() &&
                (raw.startsWith("turn:") || raw.startsWith("turns:"))
            ) {
                builder.setUsername(username).setPassword(credential)
            }
            builder.createIceServer()
        }
    }

    private inner class SdpObserver(
        private val onSuccess: (SessionDescription) -> Unit = {},
        private val onSetComplete: () -> Unit = {},
    ) : org.webrtc.SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            sdp?.let(onSuccess)
        }
        override fun onSetSuccess() = onSetComplete()
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "mesh SDP create failed: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "mesh SDP set failed: $error")
        }
    }

    class MeshPeer(val remoteDeviceId: String) {
        var pc: PeerConnection? = null
        var remoteApplied: Boolean = false
        val pendingIce = mutableListOf<IceCandidate>()
    }

    companion object {
        private const val TAG = "ViroMeshRtc"
    }
}
