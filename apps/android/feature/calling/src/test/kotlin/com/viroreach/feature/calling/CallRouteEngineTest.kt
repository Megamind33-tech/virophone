package com.viroreach.feature.calling

import com.viroreach.core.model.CallRouteType
import com.viroreach.core.model.TransportAvailability
import com.viroreach.transport.lan.CallTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CallRouteEngineTest {
    private class FakeTransport(
        override val routeType: CallRouteType,
        private val available: Boolean,
        private val latency: Int?
    ) : CallTransport {
        override suspend fun checkAvailability() =
            if (available) TransportAvailability.AVAILABLE else TransportAvailability.UNAVAILABLE
        override suspend fun connect(peerEphemeralId: String, sessionMaterial: Map<String, String>) = Result.success(Unit)
        override suspend fun disconnect() {}
        override fun getLatencyEstimateMs() = latency
    }

    @Test
    fun `prefers LAN over internet`() = runTest {
        val engine = CallRouteEngine(listOf(
            FakeTransport(CallRouteType.LAN, true, 5),
            FakeTransport(CallRouteType.INTERNET_P2P, true, 50),
        ))
        val route = engine.selectRoute()
        assertNotNull(route)
        assertEquals(CallRouteType.LAN, route!!.routeType)
    }

    @Test
    fun `falls back to TURN when direct paths unavailable`() = runTest {
        val engine = CallRouteEngine(listOf(
            FakeTransport(CallRouteType.LAN, false, null),
            FakeTransport(CallRouteType.WIFI_DIRECT, false, null),
            FakeTransport(CallRouteType.INTERNET_P2P, false, null),
            FakeTransport(CallRouteType.TURN_RELAY, true, 100),
        ))
        val route = engine.selectRoute()
        assertEquals(CallRouteType.TURN_RELAY, route?.routeType)
    }
}
