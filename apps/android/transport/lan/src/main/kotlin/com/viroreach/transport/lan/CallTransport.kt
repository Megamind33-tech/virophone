package com.viroreach.transport.lan

import com.viroreach.core.model.CallRouteType
import com.viroreach.core.model.TransportAvailability

/**
 * Transport abstraction — the rest of the application must not care
 * which underlying transport carries the voice session.
 */
interface CallTransport {
    val routeType: CallRouteType
    suspend fun checkAvailability(): TransportAvailability
    suspend fun connect(peerEphemeralId: String, sessionMaterial: Map<String, String>): Result<Unit>
    suspend fun disconnect()
    fun getLatencyEstimateMs(): Int?
}
