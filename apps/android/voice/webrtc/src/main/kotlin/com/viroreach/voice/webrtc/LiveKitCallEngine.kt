package com.viroreach.voice.webrtc

import android.content.Context
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import com.viroreach.core.model.CallStateMachineState
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioPresets
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import livekit.org.webrtc.PeerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the LiveKit [Room] connection that carries call audio. This replaces
 * [WebRtcVoiceEngine] as the media transport — Viro's own signaling (ring,
 * accept, reject, end) is unchanged and lives entirely in CallManager; this
 * class only ever runs after a call has been accepted, to move audio.
 *
 * [callState] mirrors the same states CallManager's state machine cares
 * about (CONNECTING/ACTIVE/RECONNECTING/MEDIA_FAILED/ENDED) so CallManager
 * can bridge it in exactly the way it bridged WebRtcVoiceEngine.callState —
 * ACTIVE is only reported once a remote track is actually subscribed, never
 * merely on room-connected, so "accepted" can never be mistaken for
 * "media flowing".
 */
/** A remote room participant — used by group calls to render a roster; 1:1 calls can ignore this. */
data class LiveKitParticipant(
    val identity: String,
    val isSpeaking: Boolean = false,
)

class LiveKitCallEngine(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: Room? = null
    private var eventsJob: Job? = null
    private var reconnectWatchdogJob: Job? = null

    private val _callState = MutableStateFlow(CallStateMachineState.IDLE)
    val callState: StateFlow<CallStateMachineState> = _callState.asStateFlow()

    // A Room already supports any number of participants — this just surfaces
    // that as a roster for group calls. 1:1 calls have exactly one entry here
    // and can keep relying on callState alone, same as before.
    private val _participants = MutableStateFlow<List<LiveKitParticipant>>(emptyList())
    val participants: StateFlow<List<LiveKitParticipant>> = _participants.asStateFlow()

    // callState's RECONNECTING only ever reflects THIS device's own room
    // connection — when the *other* person's network drops, their audio just
    // goes silent here with no explanation. This tracks the remote side's
    // reported connection quality instead, so the local UI can say "their
    // connection is unstable" rather than leaving that silence unexplained.
    private val _remotePeerUnstable = MutableStateFlow(false)
    val remotePeerUnstable: StateFlow<Boolean> = _remotePeerUnstable.asStateFlow()

    private var pendingMuted = false
    private var pendingSpeaker = false

    /**
     * [forceRelay] mirrors the old engineering "Force TURN UDP/TCP" diagnostic —
     * useful for proving a call survives symmetric NAT / hostile networks
     * (see docs/PHASE_1A_HARDWARE_TEST.md). AUTO (false) lets LiveKit pick the
     * best path itself, same as normal calls.
     */
    suspend fun connect(url: String, token: String, forceRelay: Boolean = false) {
        disconnect()
        _callState.value = CallStateMachineState.CONNECTING
        // This is a phone call, not a video meeting — LiveKit's default audio
        // publish settings are tuned for the latter (clearer, higher-bitrate,
        // meeting-room speech). TELEPHONE is the SDK's own low-bandwidth voice
        // preset; DTX stops sending packets during silence (real data savings
        // on a two-way conversation); RED sends redundant audio data so a lost
        // packet doesn't drop a syllable — the two together are the standard
        // WebRTC techniques for holding up over a poor connection.
        val roomOptions = RoomOptions(
            audioTrackPublishDefaults = AudioTrackPublishDefaults(
                audioBitrate = AudioPresets.TELEPHONE.maxBitrate,
                dtx = true,
                red = true,
            ),
        )
        val newRoom = LiveKit.create(appContext, roomOptions)
        room = newRoom
        eventsJob = scope.launch {
            newRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.TrackSubscribed -> {
                        log("REMOTE_AUDIO_SUBSCRIBED participant=${event.participant.identity}")
                        _callState.value = CallStateMachineState.ACTIVE
                    }
                    is RoomEvent.ParticipantConnected -> {
                        val identity = event.participant.identity.toString()
                        log("PARTICIPANT_CONNECTED identity=$identity")
                        addParticipant(identity)
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        val identity = event.participant.identity.toString()
                        log("PARTICIPANT_DISCONNECTED identity=$identity")
                        removeParticipant(identity)
                    }
                    is RoomEvent.ActiveSpeakersChanged -> {
                        val speaking = event.speakers.map { it.identity.toString() }.toSet()
                        _participants.value = _participants.value.map {
                            it.copy(isSpeaking = it.identity in speaking)
                        }
                    }
                    is RoomEvent.ConnectionQualityChanged -> {
                        if (event.participant is io.livekit.android.room.participant.RemoteParticipant) {
                            val unstable = event.quality == io.livekit.android.room.participant.ConnectionQuality.POOR ||
                                event.quality == io.livekit.android.room.participant.ConnectionQuality.LOST
                            if (unstable != _remotePeerUnstable.value) {
                                log("REMOTE_QUALITY_CHANGED participant=${event.participant.identity} quality=${event.quality}")
                            }
                            _remotePeerUnstable.value = unstable
                        }
                    }
                    is RoomEvent.Reconnecting -> {
                        log("LIVEKIT_RECONNECTING")
                        _callState.value = CallStateMachineState.RECONNECTING
                        // LiveKit's own auto-reconnect can give up internally
                        // without ever firing Reconnected or Disconnected —
                        // network back up or not, the call sat on
                        // "Reconnecting…" forever with no way out but force-
                        // quitting the app. This is the backstop.
                        reconnectWatchdogJob?.cancel()
                        reconnectWatchdogJob = scope.launch {
                            delay(RECONNECT_TIMEOUT_MS)
                            log("LIVEKIT_RECONNECT_TIMEOUT")
                            _callState.value = CallStateMachineState.MEDIA_FAILED
                            runCatching { room?.disconnect() }
                        }
                    }
                    is RoomEvent.Reconnected -> {
                        log("LIVEKIT_ROOM_RECONNECTED")
                        reconnectWatchdogJob?.cancel()
                        reconnectWatchdogJob = null
                        _callState.value = CallStateMachineState.ACTIVE
                    }
                    is RoomEvent.Disconnected -> {
                        log("LIVEKIT_ROOM_DISCONNECTED reason=${event.reason}")
                        reconnectWatchdogJob?.cancel()
                        reconnectWatchdogJob = null
                        _callState.value = CallStateMachineState.ENDED
                    }
                    is RoomEvent.FailedToConnect -> {
                        log("LIVEKIT_CONNECTION_FAILED error=${event.error.message}")
                        _callState.value = CallStateMachineState.MEDIA_FAILED
                    }
                    else -> Unit
                }
            }
        }
        try {
            log("LIVEKIT_CONNECTING url=$url forceRelay=$forceRelay")
            val options = if (forceRelay) {
                ConnectOptions(
                    rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                        iceTransportsType = PeerConnection.IceTransportsType.RELAY
                    },
                )
            } else {
                ConnectOptions()
            }
            newRoom.connect(url, token, options)
            log("LIVEKIT_ROOM_CONNECTED")
            // ParticipantConnected only fires for people who join *after* us —
            // anyone already in the room needs this explicit snapshot.
            _participants.value = newRoom.remoteParticipants.values.map {
                LiveKitParticipant(it.identity.toString())
            }
            newRoom.localParticipant.setMicrophoneEnabled(!pendingMuted)
            log("LOCAL_AUDIO_PUBLISHED")
            applySpeaker(pendingSpeaker)
        } catch (e: Exception) {
            log("LIVEKIT_CONNECTION_FAILED error=${e.message}")
            _callState.value = CallStateMachineState.MEDIA_FAILED
            throw e
        }
    }

    fun setMuted(muted: Boolean) {
        pendingMuted = muted
        val r = room ?: return
        scope.launch { runCatching { r.localParticipant.setMicrophoneEnabled(!muted) } }
    }

    fun setSpeaker(enabled: Boolean) {
        pendingSpeaker = enabled
        applySpeaker(enabled)
    }

    private fun applySpeaker(enabled: Boolean) {
        val handler = room?.audioSwitchHandler ?: return
        val target = handler.availableAudioDevices.firstOrNull {
            if (enabled) it is AudioDevice.Speakerphone else it is AudioDevice.Earpiece
        }
        if (target != null) handler.selectDevice(target)
    }

    suspend fun disconnect() {
        eventsJob?.cancel()
        eventsJob = null
        reconnectWatchdogJob?.cancel()
        reconnectWatchdogJob = null
        room?.let { r -> runCatching { r.disconnect() } }
        room = null
        _callState.value = CallStateMachineState.IDLE
        _participants.value = emptyList()
        _remotePeerUnstable.value = false
    }

    private fun addParticipant(identity: String) {
        if (_participants.value.any { it.identity == identity }) return
        _participants.value = _participants.value + LiveKitParticipant(identity)
    }

    private fun removeParticipant(identity: String) {
        _participants.value = _participants.value.filter { it.identity != identity }
    }

    private fun log(event: String) {
        Log.i(TAG, event)
    }

    companion object {
        private const val TAG = "ViroLiveKit"
        private const val RECONNECT_TIMEOUT_MS = 30_000L
    }
}
