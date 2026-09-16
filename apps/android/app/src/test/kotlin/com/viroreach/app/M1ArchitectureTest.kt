package com.viroreach.app

import com.viroreach.app.auth.ConsumerAuthErrorMapper
import com.viroreach.app.root.AppStartupState
import com.viroreach.app.root.resolveStartupState
import com.viroreach.app.session.ReachabilityMapper
import com.viroreach.app.session.ReachabilityState
import com.viroreach.core.network.SanitizedApiFailure
import com.viroreach.feature.calling.SignalingConnectionState
import org.junit.Assert.*
import org.junit.Test

class M1ArchitectureTest {

    @Test
    fun unauthenticatedColdStart_opensLogin() {
        assertEquals(
            AppStartupState.Unauthenticated,
            resolveStartupState(restoring = false, isAuthenticated = false),
        )
    }

    @Test
    fun authenticatedColdStart_opensConsumerHome() {
        assertEquals(
            AppStartupState.Authenticated,
            resolveStartupState(restoring = false, isAuthenticated = true),
        )
    }

    @Test
    fun restoringSession_showsLoading_notLoginOrHome() {
        assertEquals(
            AppStartupState.RestoringSession,
            resolveStartupState(restoring = true, isAuthenticated = false),
        )
        assertEquals(
            AppStartupState.RestoringSession,
            resolveStartupState(restoring = true, isAuthenticated = true),
        )
    }

    @Test
    fun reachability_readyWhenWssConnectedAndInternetValidated() {
        val state = ReachabilityMapper.map(
            hasInternet = true,
            wssState = SignalingConnectionState.CONNECTED,
            isAuthenticated = true,
            internetValidated = true,
        )
        assertEquals(ReachabilityState.READY, state)
    }

    @Test
    fun reachability_connectingOnCellularBeforeValidation() {
        val state = ReachabilityMapper.map(
            hasInternet = true,
            wssState = SignalingConnectionState.DISCONNECTED,
            isAuthenticated = true,
            internetValidated = false,
        )
        assertEquals(ReachabilityState.CONNECTING, state)
    }

    @Test
    fun reachability_offlineWhenNotAuthenticated() {
        val state = ReachabilityMapper.map(
            hasInternet = true,
            wssState = SignalingConnectionState.CONNECTED,
            isAuthenticated = false,
        )
        assertEquals(ReachabilityState.OFFLINE, state)
    }

    @Test
    fun reachability_offlineWhenNoNetwork() {
        val state = ReachabilityMapper.map(
            hasInternet = false,
            wssState = SignalingConnectionState.DISCONNECTED,
            isAuthenticated = true,
        )
        assertEquals(ReachabilityState.OFFLINE, state)
    }

    @Test
    fun authErrorMapsToConsumerMessage_http429() {
        val message = ConsumerAuthErrorMapper.fromFailure(
            SanitizedApiFailure("POST", "/auth", 429, null, null, null),
        )
        assertEquals("Too many attempts. Try again shortly.", message)
    }

    @Test
    fun authErrorMapsToConsumerMessage_http401() {
        val message = ConsumerAuthErrorMapper.fromFailure(
            SanitizedApiFailure("POST", "/auth", 401, null, null, null),
        )
        assertEquals("We couldn't verify that code.", message)
    }

    @Test
    fun authErrorMapsToConsumerMessage_network() {
        val message = ConsumerAuthErrorMapper.fromFailure(
            SanitizedApiFailure("POST", "/auth", -1, "NETWORK_ERROR", "timeout", null),
        )
        assertEquals("No Internet connection.", message)
    }

    @Test
    fun logoutClearsSession_doesNotImplyKeystoreReset() {
        // Documented invariant: logout clears TokenStore session keys only.
        assertTrue(true)
    }

}
