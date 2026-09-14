package com.viroreach.transport.wifidirect

import com.viroreach.core.model.CallRouteType
import com.viroreach.core.model.TransportAvailability
import com.viroreach.transport.lan.CallTransport

class WifiDirectCallTransport : CallTransport {
    override val routeType = CallRouteType.WIFI_DIRECT

    override suspend fun checkAvailability(): TransportAvailability {
        return TransportAvailability.UNAVAILABLE
    }

    override suspend fun connect(peerEphemeralId: String, sessionMaterial: Map<String, String>): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Wi-Fi Direct not yet connected"))
    }

    override suspend fun disconnect() {}
    override fun getLatencyEstimateMs(): Int? = 10
}
