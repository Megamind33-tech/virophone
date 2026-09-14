package com.viroreach.transport.internet

import com.viroreach.core.model.CallRouteType
import com.viroreach.core.model.TransportAvailability
import com.viroreach.transport.lan.CallTransport

class InternetSipCallTransport : CallTransport {
    override val routeType = CallRouteType.INTERNET_P2P

    override suspend fun checkAvailability(): TransportAvailability {
        return TransportAvailability.AVAILABLE
    }

    override suspend fun connect(peerEphemeralId: String, sessionMaterial: Map<String, String>): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun disconnect() {}
    override fun getLatencyEstimateMs(): Int? = 50
}
