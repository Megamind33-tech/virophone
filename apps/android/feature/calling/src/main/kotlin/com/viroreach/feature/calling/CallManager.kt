package com.viroreach.feature.calling

import android.content.Context
import com.viroreach.core.model.CallStateMachineState
import com.viroreach.core.network.TokenStore
import com.viroreach.core.network.ViroApiClient
import com.viroreach.voice.api.VoiceEngine
import com.viroreach.voice.webrtc.WebRtcVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Orchestrates authorize → signaling → WebRTC for Phase 1A engineering calls.
 */
class CallManager(
    context: Context,
    private val tokenStore: TokenStore,
    private val forceRelay: Boolean = false,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = ViroApiClient(tokenStore).api
    private val signaling = SignalingClient()
    private val voiceEngine: WebRtcVoiceEngine = WebRtcVoiceEngine(context)

    private val _state = MutableStateFlow(CallStateMachineState.IDLE)
    val state: StateFlow<CallStateMachineState> = _state.asStateFlow()

    private val _routeLabel = MutableStateFlow("NOT AVAILABLE")
    val routeLabel: StateFlow<String> = _routeLabel.asStateFlow()

    private var activeCallId: String? = null
    private var calleeDeviceId: String? = null
    private var isCaller = false

    init {
        scope.launch { voiceEngine.initialize() }
        signaling.incoming.onEach { handleSignalingMessage(it) }.launchIn(scope)
    }

    suspend fun connectSignaling() {
        val token = tokenStore.getAccessToken() ?: return
        val base = com.viroreach.core.network.BuildConfig.API_BASE_URL
            .replace("http://", "ws://")
            .replace("https://", "wss://")
        signaling.connect("$base/api/v1/signaling/ws", token)
    }

    suspend fun startCall(targetUserId: String) {
        _state.value = CallStateMachineState.AUTHORIZING
        val auth = api.authorizeCall(
            com.viroreach.core.network.AuthorizeCallBody(targetUserId, if (forceRelay) "TURN_RELAY" else "INTERNET_P2P"),
        )
        if (!auth.authorized) {
            _state.value = CallStateMachineState.UNAUTHORIZED
            return
        }
        activeCallId = auth.callId
        calleeDeviceId = auth.sessionMaterial?.get("calleeDeviceId")
        isCaller = true
        _state.value = CallStateMachineState.SIGNALING

        val turn = api.getTurnCredentials()
        val iceServers = turn.urls.joinToString(",")
        voiceEngine.startCall(
            targetUserId,
            mapOf("callId" to auth.callId, "iceServers" to iceServers),
        )

        voiceEngine.createOffer { sdp ->
            signaling.send(
                "call.invite",
                auth.callId,
                calleeDeviceId,
                JSONObject().put("sdp", sdp.description).put("type", sdp.type.canonicalForm()),
            )
            signaling.send("call.offer", auth.callId, calleeDeviceId, JSONObject().put("sdp", sdp.description))
        }
        _state.value = CallStateMachineState.RINGING
    }

    suspend fun acceptCall(callId: String) {
        activeCallId = callId
        isCaller = false
        _state.value = CallStateMachineState.CONNECTING
        val turn = api.getTurnCredentials()
        voiceEngine.acceptCall(callId)
        voiceEngine.createAnswer { sdp ->
            signaling.send(
                "call.answer",
                callId,
                payload = JSONObject().put("sdp", sdp.description).put("type", sdp.type.canonicalForm()),
            )
        }
        signaling.send("call.accept", callId)
    }

    fun setMuted(muted: Boolean) = voiceEngine.setMuted(muted)
    fun setSpeaker(on: Boolean) = voiceEngine.setSpeaker(on)

    suspend fun hangUp() {
        activeCallId?.let { id ->
            signaling.send("call.end", id)
            voiceEngine.endCall(id)
            try { api.endCall(id) } catch (_: Exception) {}
        }
        _state.value = CallStateMachineState.ENDED
        activeCallId = null
    }

    private fun handleSignalingMessage(msg: SignalingMessage) {
        when (msg.type) {
            "call.invite", "call.ringing" -> _state.value = CallStateMachineState.RINGING
            "call.offer" -> {
                val sdp = msg.payload?.getString("sdp") ?: return
                voiceEngine.setRemoteDescription(
                    SessionDescription(SessionDescription.Type.OFFER, sdp),
                ) {
                    scope.launch {
                        if (!isCaller) {
                            voiceEngine.createAnswer { answer ->
                                signaling.send("call.answer", msg.callId, payload = JSONObject().put("sdp", answer.description))
                            }
                        }
                    }
                }
            }
            "call.answer" -> {
                val sdp = msg.payload?.getString("sdp") ?: return
                voiceEngine.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                _state.value = CallStateMachineState.ACTIVE
                _routeLabel.value = if (forceRelay) "TURN (forced)" else "P2P/TURN (auto)"
            }
            "call.ice" -> {
                val payload = msg.payload ?: return
                val candidate = IceCandidate(
                    payload.optString("sdpMid"),
                    payload.optInt("sdpMLineIndex"),
                    payload.getString("candidate"),
                )
                voiceEngine.addIceCandidate(candidate)
            }
            "call.end", "call.reject" -> {
                _state.value = CallStateMachineState.ENDED
            }
            "call.error" -> _state.value = CallStateMachineState.FAILED
        }
    }

    fun shutdown() {
        signaling.disconnect()
        scope.launch { voiceEngine.shutdown() }
    }
}
