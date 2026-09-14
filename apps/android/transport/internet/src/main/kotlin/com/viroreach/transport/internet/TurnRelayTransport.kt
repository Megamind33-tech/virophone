package com.viroreach.transport.internet

import com.viroreach.core.model.CallRouteType
import com.viroreach.core.model.TransportAvailability
import com.viroreach.transport.lan.CallTransport

class TurnRelayTransport : CallTransport {
    override val routeType = CallRouteType.TURN_RELAY

    override suspend fun checkAvailability(): TransportAvailability {
        return TransportAvailability.AVAILABLE
    }

    override suspend fun connect(peerEphemeralId: String, sessionMaterial: Map<String, String>): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun disconnect() {}
    override fun getLatencyEstimateMs(): Int? = 100
}
