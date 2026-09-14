package com.viroreach.feature.calling

import com.viroreach.core.model.CallRouteType
import com.viroreach.core.model.TransportAvailability
import com.viroreach.transport.lan.CallTransport

/**
 * Selects the best transport for a call.
 * Priority: LAN > Wi-Fi Direct > Internet P2P > TURN relay
 * Identity authorization happens BEFORE route selection.
 */
class CallRouteEngine(
    private val transports: List<CallTransport>
) {
    data class RouteSelection(
        val transport: CallTransport,
        val routeType: CallRouteType,
        val estimatedLatencyMs: Int?
    )

    suspend fun selectRoute(
        authorizedPeerEphemeralId: String? = null,
        peerCapabilities: Set<CallRouteType> = emptySet()
    ): RouteSelection? {
        val priorityOrder = listOf(
            CallRouteType.LAN,
            CallRouteType.WIFI_DIRECT,
            CallRouteType.INTERNET_P2P,
            CallRouteType.TURN_RELAY
        )

        for (routeType in priorityOrder) {
            val transport = transports.find { it.routeType == routeType } ?: continue
            if (transport.checkAvailability() != TransportAvailability.AVAILABLE) continue
            if (peerCapabilities.isNotEmpty() && routeType !in peerCapabilities) continue
            return RouteSelection(transport, routeType, transport.getLatencyEstimateMs())
        }
        return null
    }
}
