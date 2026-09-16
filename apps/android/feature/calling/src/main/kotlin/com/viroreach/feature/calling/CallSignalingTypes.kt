package com.viroreach.feature.calling

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class SignalingMessage(
    val type: String,
    val callId: String,
    val fromUserId: String? = null,
    val fromDeviceId: String? = null,
    val payload: JSONObject? = null,
)

enum class SignalingRoute {
    NONE,
    WSS,
    LOCAL_LAN,
    WIFI_DIRECT,
    VIRO_PRIVATE,
}

enum class SignalingConnectionState {
    STOPPED,
    LISTENING,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    RECONNECTING,
    FAILED,
    DISCONNECTED,
}

interface CallSignalingTransport {
    val route: SignalingRoute
    val connectionState: StateFlow<SignalingConnectionState>
    val incoming: kotlinx.coroutines.flow.Flow<SignalingMessage>
    fun connect(config: SignalingConnectConfig = SignalingConnectConfig())
    fun send(type: String, callId: String, targetDeviceId: String? = null, payload: JSONObject? = null)
    fun disconnect()
}

data class SignalingConnectConfig(
    val wssUrl: String? = null,
    val accessToken: String? = null,
    val forceReconnect: Boolean = false,
    val peerHost: String? = null,
    val peerPort: Int = LocalSignalingServer.DEFAULT_PORT,
    val peerUserId: String? = null,
    val peerEphemeralId: String? = null,
    val peerBindingTag: String? = null,
)

data class LocalPeerEndpoint(
    val ephemeralId: String,
    val hostAddress: String,
    val signalingPort: Int,
    val bindingTag: String?,
    val peerUserId: String? = null,
)
