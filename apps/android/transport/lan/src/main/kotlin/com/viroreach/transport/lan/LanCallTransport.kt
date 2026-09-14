package com.viroreach.transport.lan

import com.viroreach.core.model.CallRouteType
import com.viroreach.core.model.TransportAvailability

class LanCallTransport : CallTransport {
    override val routeType = CallRouteType.LAN

    override suspend fun checkAvailability(): TransportAvailability {
        // Phase 0: NSD/mDNS availability check stub
        return TransportAvailability.AVAILABLE
    }

    override suspend fun connect(peerEphemeralId: String, sessionMaterial: Map<String, String>): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun disconnect() {}

    override fun getLatencyEstimateMs(): Int? = 5
}
