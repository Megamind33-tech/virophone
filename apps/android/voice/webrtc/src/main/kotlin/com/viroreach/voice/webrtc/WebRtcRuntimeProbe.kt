package com.viroreach.voice.webrtc

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.PeerConnectionFactory

enum class WebRtcEngineState {
    NOT_STARTED,
    INITIALIZING,
    READY,
    FAILED,
}

/**
 * Shared runtime probe for WebRTC initialization (engineering diagnostics).
 */
object WebRtcRuntimeProbe {
    private val mutex = Mutex()
    private var state: WebRtcEngineState = WebRtcEngineState.NOT_STARTED
    private var failureReason: String? = null
    private var factory: PeerConnectionFactory? = null

    fun currentState(): WebRtcEngineState = state
    fun failureReason(): String? = failureReason

    suspend fun ensureInitialized(context: Context): WebRtcEngineState = mutex.withLock {
        if (state == WebRtcEngineState.READY) return state
        if (state == WebRtcEngineState.FAILED) return state
        state = WebRtcEngineState.INITIALIZING
        failureReason = null
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            factory?.dispose()
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            state = WebRtcEngineState.READY
            state
        } catch (e: Exception) {
            failureReason = e.message?.take(120)
            state = WebRtcEngineState.FAILED
            state
        }
    }

    fun shutdown() {
        factory?.dispose()
        factory = null
        state = WebRtcEngineState.NOT_STARTED
        failureReason = null
    }
}
