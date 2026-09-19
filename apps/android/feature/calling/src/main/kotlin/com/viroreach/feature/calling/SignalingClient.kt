package com.viroreach.feature.calling

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class WssConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    AUTH_FAILED,
    REVOKED,
    FAILED,
}

class SignalingClient {
    private var webSocket: WebSocket? = null
    private var connectedUrl: String? = null
    private val events = Channel<SignalingMessage>(Channel.BUFFERED)
    val incoming: Flow<SignalingMessage> = events.receiveAsFlow()

    private val _connectionState = MutableStateFlow(WssConnectionState.DISCONNECTED)
    val connectionState: StateFlow<WssConnectionState> = _connectionState.asStateFlow()

    private val _statusDetail = MutableStateFlow<String?>(null)
    val statusDetail: StateFlow<String?> = _statusDetail.asStateFlow()

    private val _lastCloseCode = MutableStateFlow<Int?>(null)
    val lastCloseCode: StateFlow<Int?> = _lastCloseCode.asStateFlow()

    private val generationCounter = AtomicInteger(0)
    private val _connectionGeneration = MutableStateFlow(0)
    val connectionGeneration: StateFlow<Int> = _connectionGeneration.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        // Some restrictive Wi-Fi routers NAT-time-out an idle connection well
        // under 30s — a shorter ping both detects a dead socket sooner and
        // keeps the NAT mapping alive longer in the first place.
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    /**
     * Connect unless already connected to the same URL.
     * [force] closes any existing socket first (manual retry only).
     */
    fun connect(signalingUrl: String, accessToken: String, force: Boolean = false) {
        if (!force &&
            _connectionState.value == WssConnectionState.CONNECTED &&
            connectedUrl == signalingUrl &&
            webSocket != null
        ) {
            return
        }
        if (force || webSocket != null) {
            disconnectInternal(userInitiated = true)
        }
        val gen = generationCounter.incrementAndGet()
        _connectionGeneration.value = gen
        _connectionState.value = WssConnectionState.CONNECTING
        _statusDetail.value = "gen=$gen CONNECTING"
        logEngineering("WSS_GEN_${gen}_CONNECTING")
        val wsUrl = if (signalingUrl.contains("?")) {
            "$signalingUrl&token=$accessToken"
        } else {
            "$signalingUrl?token=$accessToken"
        }
        connectedUrl = signalingUrl
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generationCounter.get()) return
                _connectionState.value = WssConnectionState.CONNECTED
                _statusDetail.value = "gen=$gen CONNECTED HTTP ${response.code}"
                _lastCloseCode.value = null
                logEngineering("WSS_GEN_${gen}_CONNECTED")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generationCounter.get()) return
                _lastCloseCode.value = code
                _statusDetail.value = "gen=$gen closed code=$code reason=${reason.take(80)}"
                logEngineering("WSS_GEN_${gen}_CLOSED code=$code")
                _connectionState.value = when (code) {
                    4001 -> WssConnectionState.AUTH_FAILED
                    4003 -> WssConnectionState.REVOKED
                    else -> WssConnectionState.DISCONNECTED
                }
                connectedUrl = null
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generationCounter.get()) return
                parseInboundMessage(text, gen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generationCounter.get()) return
                val http = response?.code
                val reason = classifyFailure(t, http)
                _statusDetail.value = buildString {
                    append("gen=$gen FAILED [$reason]")
                    http?.let { append(" HTTP $it") }
                    t.message?.let { append(" ${it.take(120)}") }
                }
                logEngineering("WSS_GEN_${gen}_FAILED $reason ${t.javaClass.simpleName}")
                _connectionState.value = when {
                    http == 401 || http == 403 -> WssConnectionState.AUTH_FAILED
                    isNetworkFailure(t) -> WssConnectionState.RECONNECTING
                    else -> WssConnectionState.FAILED
                }
                connectedUrl = null
            }
        })
    }

    private fun parseInboundMessage(text: String, gen: Int) {
        val parsed = SignalingFrameParser.parse(text)
        parsed.serverAckError?.let { error ->
            _statusDetail.value = "gen=$gen server ack: $error"
            logEngineering("WSS_GEN_${gen}_SERVER_ACK error=$error")
            events.trySend(
                SignalingMessage(
                    "call.error",
                    "",
                    payload = JSONObject().put("code", error),
                ),
            )
        }
        parsed.message?.let { msg ->
            logEngineering("WSS_GEN_${gen}_MESSAGE type=${msg.type}")
            events.trySend(msg)
        }
        parsed.warning?.let { warn ->
            val preview = text.take(80).replace(Regex("\\s+"), " ")
            _statusDetail.value = "gen=$gen parse warn: $warn frame=$preview"
            logEngineering("WSS_GEN_${gen}_PARSE_WARN $warn")
        }
    }

    fun send(type: String, callId: String, targetDeviceId: String? = null, payload: JSONObject? = null) {
        val gen = _connectionGeneration.value
        logEngineering("WSS_GEN_${gen}_SEND type=$type callId=${callId.take(8)}")
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

    fun sendConference(
        type: String,
        roomId: String,
        targetDeviceId: String? = null,
        payload: JSONObject? = null,
    ) {
        val gen = _connectionGeneration.value
        logEngineering("WSS_GEN_${gen}_SEND_CONF type=$type room=${roomId.take(8)}")
        val envelope = JSONObject()
            .put("type", type)
            .put("roomId", roomId)
        if (targetDeviceId != null) envelope.put("targetDeviceId", targetDeviceId)
        if (payload != null) envelope.put("payload", payload)
        webSocket?.send(
            JSONObject()
                .put("event", "conference")
                .put("data", envelope)
                .toString(),
        )
    }

    /** Chat presence ("typing…", "recording voice…"); fire-and-forget. */
    fun sendChat(data: JSONObject) {
        webSocket?.send(
            JSONObject()
                .put("event", "chat")
                .put("data", data)
                .toString(),
        )
    }

    fun disconnect() = disconnectInternal(userInitiated = true)

    private fun disconnectInternal(userInitiated: Boolean) {
        val gen = _connectionGeneration.value
        if (userInitiated) {
            logEngineering("WSS_GEN_${gen}_DISCONNECT_REQUESTED")
        }
        webSocket?.close(1000, "bye")
        webSocket = null
        connectedUrl = null
        _connectionState.value = WssConnectionState.DISCONNECTED
        _statusDetail.value = "gen=$gen DISCONNECTED"
    }

    private fun classifyFailure(t: Throwable, http: Int?): String = when {
        http == 401 -> "AUTH_ERROR"
        http == 403 -> "AUTH_ERROR"
        http != null && http >= 500 -> "SERVER_ERROR"
        isNetworkFailure(t) -> "NETWORK/DNS"
        else -> "PROTOCOL/LOCAL"
    }

    private fun isNetworkFailure(t: Throwable): Boolean {
        val msg = t.message?.lowercase() ?: return false
        return msg.contains("unable to resolve host") ||
            msg.contains("network is unreachable") ||
            msg.contains("connection abort") ||
            msg.contains("connection reset") ||
            msg.contains("failed to connect") ||
            msg.contains("timeout")
    }

    private fun logEngineering(event: String) {
        Log.i(TAG, event)
    }

    companion object {
        private const val TAG = "ViroWss"
    }
}
