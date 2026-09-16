package com.viroreach.app.session

import com.viroreach.core.designsystem.components.ViroReachabilityVisual
import com.viroreach.feature.calling.SignalingConnectionState

enum class ReachabilityState {
    READY,
    CONNECTING,
    LIMITED,
    OFFLINE,
    RECONNECTING,
}

object ReachabilityMapper {
    fun map(
        hasInternet: Boolean,
        wssState: SignalingConnectionState,
        isAuthenticated: Boolean,
        internetValidated: Boolean = hasInternet,
    ): ReachabilityState {
        if (!isAuthenticated) return ReachabilityState.OFFLINE
        if (!hasInternet) return ReachabilityState.OFFLINE
        return when (wssState) {
            SignalingConnectionState.CONNECTED -> ReachabilityState.READY
            SignalingConnectionState.CONNECTING,
            SignalingConnectionState.AUTHENTICATING,
            -> ReachabilityState.CONNECTING
            SignalingConnectionState.RECONNECTING -> ReachabilityState.RECONNECTING
            SignalingConnectionState.FAILED -> {
                if (internetValidated) ReachabilityState.LIMITED else ReachabilityState.CONNECTING
            }
            SignalingConnectionState.DISCONNECTED -> {
                if (internetValidated) ReachabilityState.CONNECTING else ReachabilityState.CONNECTING
            }
            SignalingConnectionState.STOPPED,
            SignalingConnectionState.LISTENING,
            -> ReachabilityState.LIMITED
        }
    }

    fun toVisual(state: ReachabilityState): ViroReachabilityVisual = when (state) {
        ReachabilityState.READY -> ViroReachabilityVisual.READY
        ReachabilityState.CONNECTING -> ViroReachabilityVisual.CONNECTING
        ReachabilityState.LIMITED -> ViroReachabilityVisual.LIMITED
        ReachabilityState.OFFLINE -> ViroReachabilityVisual.OFFLINE
        ReachabilityState.RECONNECTING -> ViroReachabilityVisual.RECONNECTING
    }
}
