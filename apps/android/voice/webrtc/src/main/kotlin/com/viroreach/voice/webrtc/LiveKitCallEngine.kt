package com.viroreach.voice.webrtc

import android.content.Context
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import com.viroreach.core.model.CallStateMachineState
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import livekit.org.webrtc.PeerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
class LiveKitCallEngine(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: Room? = null
    private var eventsJob: Job? = null

    private val _callState = MutableStateFlow(CallStateMachineState.IDLE)
    val callState: StateFlow<CallStateMachineState> = _callState.asStateFlow()

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
        val newRoom = LiveKit.create(appContext)
        room = newRoom
        eventsJob = scope.launch {
            newRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.TrackSubscribed -> {
                        log("REMOTE_AUDIO_SUBSCRIBED participant=${event.participant.identity}")
                        _callState.value = CallStateMachineState.ACTIVE
                    }
                    is RoomEvent.Reconnecting -> {
                        log("LIVEKIT_RECONNECTING")
                        _callState.value = CallStateMachineState.RECONNECTING
                    }
                    is RoomEvent.Reconnected -> {
                        log("LIVEKIT_ROOM_RECONNECTED")
                        _callState.value = CallStateMachineState.ACTIVE
                    }
                    is RoomEvent.Disconnected -> {
                        log("LIVEKIT_ROOM_DISCONNECTED reason=${event.reason}")
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
        room?.let { r -> runCatching { r.disconnect() } }
        room = null
        _callState.value = CallStateMachineState.IDLE
    }

    private fun log(event: String) {
        Log.i(TAG, event)
    }

    companion object {
        private const val TAG = "ViroLiveKit"
    }
}
