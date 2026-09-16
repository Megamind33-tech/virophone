package com.viroreach.feature.calling

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * LAN WebSocket server for offline trusted-peer signaling.
 * Requires signaling.auth before accepting call messages.
 */
class LocalSignalingServer(
    port: Int = DEFAULT_PORT,
    private val authenticator: LocalPeerAuthenticator,
) : WebSocketServer(InetSocketAddress(port)) {
    private val events = Channel<SignalingMessage>(Channel.BUFFERED)
    val incoming: Flow<SignalingMessage> = events.receiveAsFlow()

    private val authenticated = ConcurrentHashMap<WebSocket, String>()
    @Volatile
    private var running = false

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        authenticated.remove(conn)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        authenticated.remove(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val json = JSONObject(message)
        val type = json.getString("type")

        if (type == "signaling.auth") {
            val payload = json.optJSONObject("payload") ?: return
            val eid = payload.optString("ephemeralId", "")
            val btag = payload.optString("bindingTag", "")
            if (eid.isBlank() || btag.isBlank()) {
                conn.close(4403, "auth required")
                return
            }
            val peerUserId = authenticator.validateInbound(eid, btag)
            if (peerUserId == null) {
                conn.close(4403, "unauthorized peer")
                return
            }
            authenticated[conn] = peerUserId
            conn.send(JSONObject().put("type", "signaling.auth.ok").put("callId", "").toString())
            return
        }

        if (!authenticated.containsKey(conn)) {
            conn.close(4401, "authenticate first")
            return
        }

        events.trySend(
            SignalingMessage(
                type = type,
                callId = json.getString("callId"),
                fromUserId = authenticated[conn],
                fromDeviceId = json.optString("fromDeviceId", null),
                payload = json.optJSONObject("payload"),
            ),
        )
    }

    override fun onError(conn: WebSocket?, ex: Exception) {}

    override fun onStart() {
        running = true
    }

    fun sendToPeer(peerUserId: String, message: JSONObject) {
        authenticated.entries.firstOrNull { it.value == peerUserId }?.key?.send(message.toString())
    }

    fun broadcastAuthenticated(message: JSONObject) {
        authenticated.keys.forEach { it.send(message.toString()) }
    }

    val isRunning: Boolean get() = running

    fun stopServer() {
        running = false
        stop()
    }

    companion object {
        const val DEFAULT_PORT = 8765
    }
}
