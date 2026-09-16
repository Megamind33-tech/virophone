package com.viroreach.feature.calling

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

/**
 * Authenticated LAN signaling — local WebSocket server + outbound client connections.
 */
class LocalLanSignalingTransport(
    private val scope: CoroutineScope,
    private val authenticator: LocalPeerAuthenticator,
) : CallSignalingTransport {
    override val route: SignalingRoute = SignalingRoute.LOCAL_LAN

    private val server = LocalSignalingServer(authenticator = authenticator)
    private val mergedEvents = Channel<SignalingMessage>(Channel.BUFFERED)
    override val incoming: Flow<SignalingMessage> = mergedEvents.receiveAsFlow()

    private val _connectionState = MutableStateFlow(SignalingConnectionState.STOPPED)
    override val connectionState: StateFlow<SignalingConnectionState> = _connectionState.asStateFlow()

    private var outboundClient: WebSocketClient? = null
    private var connectedPeerUserId: String? = null

    init {
        scope.launch(Dispatchers.IO) {
            server.incoming.collect { mergedEvents.trySend(it) }
        }
    }

    fun startListening() {
        if (_connectionState.value == SignalingConnectionState.LISTENING) return
        try {
            server.start()
            _connectionState.value = SignalingConnectionState.LISTENING
        } catch (e: Exception) {
            Log.e(TAG, "Local signaling listen failed", e)
            _connectionState.value = SignalingConnectionState.FAILED
        }
    }

    override fun connect(config: SignalingConnectConfig) {
        val host = config.peerHost ?: return
        val peerUserId = config.peerUserId ?: return
        disconnectOutbound()
        _connectionState.value = SignalingConnectionState.CONNECTING
        val auth = authenticator.buildOutboundAuth(peerUserId)
        if (auth == null) {
            _connectionState.value = SignalingConnectionState.FAILED
            return
        }
        val uri = URI("ws://$host:${config.peerPort}/")
        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                _connectionState.value = SignalingConnectionState.AUTHENTICATING
                send(
                    JSONObject()
                        .put("type", "signaling.auth")
                        .put("callId", "")
                        .put("payload", auth)
                        .toString(),
                )
            }

            override fun onMessage(message: String?) {
                message ?: return
                val json = JSONObject(message)
                when (json.getString("type")) {
                    "signaling.auth.ok" -> {
                        connectedPeerUserId = peerUserId
                        _connectionState.value = SignalingConnectionState.CONNECTED
                    }
                    else -> mergedEvents.trySend(
                        SignalingMessage(
                            type = json.getString("type"),
                            callId = json.getString("callId"),
                            fromUserId = peerUserId,
                            fromDeviceId = json.optString("fromDeviceId", null),
                            payload = json.optJSONObject("payload"),
                        ),
                    )
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                if (_connectionState.value != SignalingConnectionState.STOPPED) {
                    _connectionState.value = SignalingConnectionState.LISTENING
                }
            }

            override fun onError(ex: Exception?) {
                _connectionState.value = SignalingConnectionState.FAILED
            }
        }
        outboundClient = client
        client.connect()
    }

    override fun send(type: String, callId: String, targetDeviceId: String?, payload: JSONObject?) {
        val msg = JSONObject()
            .put("type", type)
            .put("callId", callId)
        if (targetDeviceId != null) msg.put("fromDeviceId", targetDeviceId)
        if (payload != null) msg.put("payload", payload)
        outboundClient?.takeIf { it.isOpen }?.send(msg.toString())
            ?: server.sendToPeer(connectedPeerUserId ?: return, msg)
    }

    override fun disconnect() {
        disconnectOutbound()
        server.stopServer()
        _connectionState.value = SignalingConnectionState.STOPPED
    }

    private fun disconnectOutbound() {
        outboundClient?.close()
        outboundClient = null
        connectedPeerUserId = null
        if (server.isRunning) {
            _connectionState.value = SignalingConnectionState.LISTENING
        }
    }

    companion object {
        private const val TAG = "LocalLanSignaling"
    }
}
