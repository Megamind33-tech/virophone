package com.viroreach.voice.webrtc

import android.content.Context
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoPreset169
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How this phone's live link to a Moment is doing. */
enum class PresenceLink { OFF, CONNECTING, LIVE, RECONNECTING, FAILED }

/** How well someone's connection is carrying them, in the terms the room needs. */
enum class PresenceQuality { GOOD, WEAK, LOST, UNKNOWN }

/** One person in the live room, as media — who they are is the Moment's business. */
data class PresencePeer(
    val identity: String,
    val cameraOn: Boolean = false,
    val micOn: Boolean = false,
    val speaking: Boolean = false,
    /** Their video exists but isn't reaching this phone: their network, or ours. */
    val videoPaused: Boolean = false,
    val quality: PresenceQuality = PresenceQuality.UNKNOWN,
)

data class PresenceSnapshot(
    val link: PresenceLink = PresenceLink.OFF,
    val cameraOn: Boolean = false,
    val micOn: Boolean = false,
    val backCamera: Boolean = false,
    val speaking: Boolean = false,
    val quality: PresenceQuality = PresenceQuality.UNKNOWN,
    val peers: List<PresencePeer> = emptyList(),
)

/** What a Moment room needs from its media, so the room can be tested without it. */
interface MomentMediaTransport {
    val state: StateFlow<PresenceSnapshot>
    suspend fun connect(url: String, token: String)
    suspend fun setCamera(on: Boolean): Boolean
    suspend fun setMicrophone(on: Boolean): Boolean
    fun flipCamera()
    suspend fun disconnect()
}

/**
 * Faces and voices in a Moment, over the same LiveKit server calls use.
 *
 * It is separate from [LiveKitCallEngine] on purpose: a call is a phone call,
 * voice-only and tuned for it; a Moment is people in a room, where a camera may
 * be the whole point. Nothing here publishes on connect — the camera and the
 * microphone are turned on only by [setCamera] / [setMicrophone], which the
 * room calls only when its person asks.
 *
 * Video is sized for phones on mobile data: 360p capture, simulcast so a
 * weak viewer gets a smaller layer, adaptive stream so video nobody is looking
 * at is not downloaded, and dynacast so layers nobody watches are not sent.
 */
class MomentPresenceEngine(private val appContext: Context) : MomentMediaTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: Room? = null
    private var eventsJob: Job? = null

    private val _state = MutableStateFlow(PresenceSnapshot())
    override val state: StateFlow<PresenceSnapshot> = _state.asStateFlow()

    /** Renderers currently showing a track, so each can be detached cleanly. */
    private val attached = mutableMapOf<TextureViewRenderer, VideoTrack>()
    private val initialised = mutableSetOf<TextureViewRenderer>()

    val connected: Boolean get() = room != null && _state.value.link != PresenceLink.OFF

    override suspend fun connect(url: String, token: String) {
        disconnect()
        _state.value = PresenceSnapshot(link = PresenceLink.CONNECTING)
        val options = RoomOptions(
            adaptiveStream = true,
            dynacast = true,
            videoTrackCaptureDefaults = LocalVideoTrackOptions(
                position = CameraPosition.FRONT,
                captureParams = VideoPreset169.H360.capture,
            ),
            videoTrackPublishDefaults = VideoTrackPublishDefaults(
                videoEncoding = VideoPreset169.H360.encoding,
                simulcast = true,
            ),
            // Voice in a room of people, not a meeting: modest bitrate, silence
            // not sent, lost packets covered.
            audioTrackPublishDefaults = AudioTrackPublishDefaults(dtx = true, red = true),
        )
        val newRoom = LiveKit.create(appContext, options)
        room = newRoom
        eventsJob = scope.launch {
            newRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.Reconnecting -> update { it.copy(link = PresenceLink.RECONNECTING) }
                    is RoomEvent.Reconnected -> { update { it.copy(link = PresenceLink.LIVE) }; refresh() }
                    is RoomEvent.Disconnected -> {
                        log("MOMENT_MEDIA_DISCONNECTED reason=${event.reason}")
                        update { PresenceSnapshot(link = if (it.link == PresenceLink.OFF) PresenceLink.OFF else PresenceLink.FAILED) }
                    }
                    is RoomEvent.FailedToConnect -> update { it.copy(link = PresenceLink.FAILED) }
                    is RoomEvent.ParticipantConnected, is RoomEvent.ParticipantDisconnected,
                    is RoomEvent.TrackSubscribed, is RoomEvent.TrackUnsubscribed,
                    is RoomEvent.TrackPublished, is RoomEvent.TrackUnpublished,
                    is RoomEvent.TrackMuted, is RoomEvent.TrackUnmuted,
                    is RoomEvent.TrackStreamStateChanged, is RoomEvent.ActiveSpeakersChanged,
                    is RoomEvent.ConnectionQualityChanged -> refresh()
                    else -> Unit
                }
            }
        }
        try {
            newRoom.connect(url, token)
            preferLoudspeaker(newRoom)
            update { it.copy(link = PresenceLink.LIVE) }
            refresh()
            log("MOMENT_MEDIA_CONNECTED")
        } catch (e: Exception) {
            log("MOMENT_MEDIA_FAILED error=${e.message}")
            update { it.copy(link = PresenceLink.FAILED) }
            throw e
        }
    }

    /** Turns this phone's camera on or off. Only ever called on its person's say-so. */
    override suspend fun setCamera(on: Boolean): Boolean {
        val r = room ?: return false
        val ok = runCatching { r.localParticipant.setCameraEnabled(on) }.getOrDefault(false)
        refresh()
        return ok
    }

    override suspend fun setMicrophone(on: Boolean): Boolean {
        val r = room ?: return false
        val ok = runCatching { r.localParticipant.setMicrophoneEnabled(on) }.getOrDefault(false)
        refresh()
        return ok
    }

    /** Front to back and back again — "look at this pot". */
    override fun flipCamera() {
        val r = room ?: return
        val track = r.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return
        val back = !_state.value.backCamera
        runCatching { track.switchCamera(position = if (back) CameraPosition.BACK else CameraPosition.FRONT) }
            .onSuccess { update { it.copy(backCamera = back) } }
    }

    /**
     * Shows someone's camera in [renderer]; `null` is this phone's own.
     * Returns false when there is nothing to show, so the caller draws a face.
     */
    fun attachVideo(identity: String?, renderer: TextureViewRenderer): Boolean {
        val r = room ?: return false
        val participant: Participant? = if (identity == null) r.localParticipant
            else r.remoteParticipants.values.firstOrNull { it.identity?.value == identity }
        val track = participant?.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack ?: return false
        if (attached[renderer] === track) return true
        detachVideo(renderer)
        if (renderer !in initialised) {
            r.initVideoRenderer(renderer)
            initialised += renderer
        }
        renderer.setMirror(identity == null && !_state.value.backCamera)
        track.addRenderer(renderer)
        attached[renderer] = track
        return true
    }

    fun detachVideo(renderer: TextureViewRenderer) {
        attached.remove(renderer)?.let { runCatching { it.removeRenderer(renderer) } }
    }

    /** Called when a renderer's view goes away for good. */
    fun releaseRenderer(renderer: TextureViewRenderer) {
        detachVideo(renderer)
        if (initialised.remove(renderer)) runCatching { renderer.release() }
    }

    override suspend fun disconnect() {
        eventsJob?.cancel()
        eventsJob = null
        attached.forEach { (renderer, track) -> runCatching { track.removeRenderer(renderer) } }
        attached.clear()
        room?.let { r ->
            // Camera and microphone off before leaving, so no capture outlives the room.
            runCatching { r.localParticipant.setCameraEnabled(false) }
            runCatching { r.localParticipant.setMicrophoneEnabled(false) }
            runCatching { r.disconnect() }
            runCatching { r.release() }
        }
        room = null
        _state.value = PresenceSnapshot()
    }

    private fun refresh() {
        val r = room ?: return
        val local = r.localParticipant
        update { current ->
            current.copy(
                cameraOn = local.isCameraEnabled,
                micOn = local.isMicrophoneEnabled,
                speaking = local.isSpeaking,
                quality = qualityOf(local.connectionQuality),
                peers = r.remoteParticipants.values.map { p ->
                    val camera = p.getTrackPublication(Track.Source.CAMERA)
                    val paused = camera != null && !camera.muted &&
                        (camera as? io.livekit.android.room.track.RemoteTrackPublication)?.let { pub ->
                            pub.subscribed && pub.track == null
                        } == true
                    PresencePeer(
                        identity = p.identity?.value ?: "",
                        cameraOn = p.isCameraEnabled,
                        micOn = p.isMicrophoneEnabled,
                        speaking = p.isSpeaking,
                        videoPaused = paused || (p.isCameraEnabled && streamPaused(camera)),
                        quality = qualityOf(p.connectionQuality),
                    )
                }.filter { it.identity.isNotEmpty() },
            )
        }
    }

    private fun streamPaused(publication: io.livekit.android.room.track.TrackPublication?): Boolean {
        val track = publication?.track ?: return false
        return track.streamState == Track.StreamState.PAUSED
    }

    private fun preferLoudspeaker(r: Room) {
        // A room is held at arm's length or propped on a counter, not at the
        // ear. Headphones, when plugged in or paired, still win.
        val handler = r.audioSwitchHandler ?: return
        val devices = handler.availableAudioDevices
        val personal = devices.firstOrNull { it is AudioDevice.BluetoothHeadset || it is AudioDevice.WiredHeadset }
        val target = personal ?: devices.firstOrNull { it is AudioDevice.Speakerphone }
        if (target != null) handler.selectDevice(target)
    }

    private fun qualityOf(q: ConnectionQuality): PresenceQuality = when (q) {
        ConnectionQuality.EXCELLENT, ConnectionQuality.GOOD -> PresenceQuality.GOOD
        ConnectionQuality.POOR -> PresenceQuality.WEAK
        ConnectionQuality.LOST -> PresenceQuality.LOST
        else -> PresenceQuality.UNKNOWN
    }

    private inline fun update(change: (PresenceSnapshot) -> PresenceSnapshot) {
        _state.value = change(_state.value)
    }

    private fun log(event: String) = Log.i(TAG, event)

    companion object {
        private const val TAG = "ViroMomentMedia"
    }
}
