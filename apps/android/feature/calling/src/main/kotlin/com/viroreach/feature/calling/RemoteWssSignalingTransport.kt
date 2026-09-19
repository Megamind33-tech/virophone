package com.viroreach.feature.calling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject

class RemoteWssSignalingTransport(
    private val scope: CoroutineScope,
    private val client: SignalingClient = SignalingClient(),
) : CallSignalingTransport {
    override val route: SignalingRoute = SignalingRoute.WSS
    override val incoming: Flow<SignalingMessage> = client.incoming
    val statusDetail: StateFlow<String?> = client.statusDetail
    val lastCloseCode: StateFlow<Int?> = client.lastCloseCode
    val connectionGeneration: StateFlow<Int> = client.connectionGeneration

    private val _connectionState = MutableStateFlow(SignalingConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<SignalingConnectionState> = _connectionState.asStateFlow()

    private var lastConnectConfig: SignalingConnectConfig? = null
    private var reconnectAttempts = 0

    init {
        client.connectionState.onEach { wss ->
            _connectionState.value = when (wss) {
                WssConnectionState.DISCONNECTED -> SignalingConnectionState.DISCONNECTED
                WssConnectionState.CONNECTING -> SignalingConnectionState.CONNECTING
                WssConnectionState.CONNECTED -> {
                    reconnectAttempts = 0
                    SignalingConnectionState.CONNECTED
                }
                WssConnectionState.RECONNECTING -> SignalingConnectionState.RECONNECTING
                WssConnectionState.AUTH_FAILED, WssConnectionState.REVOKED, WssConnectionState.FAILED ->
                    SignalingConnectionState.FAILED
            }
            if (wss == WssConnectionState.RECONNECTING) {
                scheduleReconnect()
            }
        }.launchIn(scope)
    }

    override fun connect(config: SignalingConnectConfig) {
        val url = config.wssUrl ?: return
        val token = config.accessToken ?: return
        lastConnectConfig = config
        client.connect(url, token, force = config.forceReconnect)
    }

    private fun scheduleReconnect() {
        val config = lastConnectConfig ?: return
        val url = config.wssUrl ?: return
        val token = config.accessToken ?: return
        reconnectAttempts++
        val delayMs = (1_500L * reconnectAttempts).coerceAtMost(12_000L)
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (client.connectionState.value == WssConnectionState.RECONNECTING) {
                client.connect(url, token, force = true)
            }
        }
    }

    override fun send(type: String, callId: String, targetDeviceId: String?, payload: JSONObject?) {
        client.send(type, callId, targetDeviceId, payload)
    }

    override fun sendConference(
        type: String,
        roomId: String,
        targetDeviceId: String?,
        payload: JSONObject?,
    ) {
        client.sendConference(type, roomId, targetDeviceId, payload)
    }

    fun sendChat(data: JSONObject) = client.sendChat(data)

    override fun disconnect() = client.disconnect()
}
