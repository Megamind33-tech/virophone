package com.viroreach.feature.calling

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SignalingMessage(
    val type: String,
    val callId: String,
    val fromUserId: String? = null,
    val fromDeviceId: String? = null,
    val payload: JSONObject? = null,
)

class SignalingClient {
    private var webSocket: WebSocket? = null
    private val events = Channel<SignalingMessage>(Channel.BUFFERED)
    val incoming: Flow<SignalingMessage> = events.receiveAsFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun connect(signalingUrl: String, accessToken: String) {
        disconnect()
        val wsUrl = if (signalingUrl.contains("?")) {
            "$signalingUrl&token=$accessToken"
        } else {
            "$signalingUrl?token=$accessToken"
        }
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
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

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(SignalingMessage("call.error", "", payload = JSONObject().put("reason", t.message)))
            }
        })
    }

    fun send(type: String, callId: String, targetDeviceId: String? = null, payload: JSONObject? = null) {
        val envelope = JSONObject()
            .put("type", type)
            .put("callId", callId)
        if (targetDeviceId != null) envelope.put("targetDeviceId", targetDeviceId)
        if (payload != null) envelope.put("payload", payload)
        webSocket?.send(
            JSONObject()
                .put("event", "signaling")
                .put("data", envelope)
                .toString(),
        )
    }

    fun disconnect() {
        webSocket?.close(1000, "bye")
        webSocket = null
    }
}
