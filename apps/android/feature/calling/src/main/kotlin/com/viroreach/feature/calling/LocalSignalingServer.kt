package com.viroreach.feature.calling

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * Minimal LAN WebSocket server for offline trusted-peer signaling (Phase 1A POC).
 */
class LocalSignalingServer(
    port: Int = DEFAULT_PORT,
) : WebSocketServer(InetSocketAddress(port)) {
    private val events = Channel<SignalingMessage>(Channel.BUFFERED)
    val incoming: Flow<SignalingMessage> = events.receiveAsFlow()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}

    override fun onMessage(conn: WebSocket, message: String) {
        val json = JSONObject(message)
        events.trySend(
            SignalingMessage(
                type = json.getString("type"),
                callId = json.getString("callId"),
                fromUserId = json.optString("fromUserId", null),
                fromDeviceId = json.optString("fromDeviceId", null),
                payload = json.optJSONObject("payload"),
            ),
        )
    }

    override fun onError(conn: WebSocket?, ex: Exception) {}

    override fun onStart() {}

    fun broadcast(message: JSONObject) {
        connections.forEach { it.send(message.toString()) }
    }

    companion object {
        const val DEFAULT_PORT = 8765
    }
}
