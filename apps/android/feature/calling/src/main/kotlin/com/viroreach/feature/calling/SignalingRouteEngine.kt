package com.viroreach.feature.calling

import android.content.Context
import android.net.ConnectivityManager
import com.viroreach.core.network.InternetNetworkSelector

class SignalingRouteEngine(
    private val context: Context,
    private val peerRegistry: LocalPeerRegistry,
    private val internetProbe: (() -> Boolean)? = null,
) {
    /**
     * Prefer cloud signaling whenever mobile/Wi-Fi data is available.
     * LAN is a fallback when offline but a trusted local peer is known.
     */
    fun selectRoute(targetUserId: String): SignalingRoute {
        if (internetProbe?.invoke() ?: isInternetAvailable()) {
            return SignalingRoute.WSS
        }
        val localPeer = peerRegistry.findAuthorizedPeer(targetUserId)
        if (localPeer != null && localPeer.hostAddress.isNotBlank()) {
            return SignalingRoute.LOCAL_LAN
        }
        return SignalingRoute.NONE
    }

    fun isInternetAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return InternetNetworkSelector.evaluate(cm).hasInternet
    }
}
