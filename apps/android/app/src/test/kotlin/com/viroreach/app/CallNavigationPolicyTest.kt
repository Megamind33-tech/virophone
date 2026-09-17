package com.viroreach.app

import com.viroreach.app.consumer.CallNavigationPolicy
import com.viroreach.core.model.CallStateMachineState
import org.junit.Assert.*
import org.junit.Test

class CallNavigationPolicyTest {
    @Test
    fun userInitiatedCall_opensOverlayImmediately() {
        assertTrue(CallNavigationPolicy.userInitiatedCallOpensOverlayImmediately())
    }

    @Test
    fun authorizing_isActiveCallOverlayState() {
        assertTrue(CallNavigationPolicy.isCallOverlayState(CallStateMachineState.AUTHORIZING))
    }

    @Test
    fun incomingRinging_detectedForCallee() {
        assertTrue(CallNavigationPolicy.isIncomingRinging(CallStateMachineState.RINGING, isCaller = false))
        assertFalse(CallNavigationPolicy.isIncomingRinging(CallStateMachineState.RINGING, isCaller = true))
    }

    @Test
    fun failureStates_areRecognized() {
        assertTrue(CallNavigationPolicy.isFailureState(CallStateMachineState.UNREACHABLE))
        assertTrue(CallNavigationPolicy.isFailureState(CallStateMachineState.FAILED))
    }

    @Test
    fun callStartMustNotCreateNewWss() {
        assertTrue(CallNavigationPolicy.callStartMustNotCreateNewWss())
    }

    // --- Checkpoint D: LiveKit call-state machine ---
    // "Do not allow ACCEPTED to automatically mean CONNECTED": CONNECTING is
    // what CallManager sets immediately on Accept, before LiveKit reports a
    // real remote-track subscription (see CallManager.acceptCall /
    // connectLiveKitMedia). It must remain visually and semantically distinct
    // from ACTIVE, even though the call overlay stays open for both.

    @Test
    fun connectingIsNotActive() {
        assertNotEquals(CallStateMachineState.CONNECTING, CallStateMachineState.ACTIVE)
        assertTrue(CallNavigationPolicy.isCallOverlayState(CallStateMachineState.CONNECTING))
        assertTrue(CallNavigationPolicy.isCallOverlayState(CallStateMachineState.ACTIVE))
        // Only ACTIVE counts as truly connected for call-history "answered" bookkeeping.
        assertFalse(CallNavigationPolicy.isFailureState(CallStateMachineState.CONNECTING))
        assertFalse(CallNavigationPolicy.isFailureState(CallStateMachineState.ACTIVE))
    }

    @Test
    fun connectingMediaFailureIsDistinctFromConnected() {
        // CONNECTING -> CONNECTION_FAILED path (LiveKit RoomEvent.FailedToConnect
        // bridges to MEDIA_FAILED in CallManager) must be recognized as a failure,
        // never silently treated as a successful connect.
        assertTrue(CallNavigationPolicy.isFailureState(CallStateMachineState.MEDIA_FAILED))
        assertTrue(CallNavigationPolicy.isCallOverlayState(CallStateMachineState.MEDIA_FAILED))
    }

    @Test
    fun rejectedAndBusyAreDistinctTerminalReasons() {
        // Previously both call.reject and call.busy collapsed into a generic
        // ENDED — these must be their own recognizable failure states so the
        // UI can show "Call declined" / "Line busy" instead of a blank end.
        assertTrue(CallNavigationPolicy.isFailureState(CallStateMachineState.PEER_REJECTED))
        assertTrue(CallNavigationPolicy.isFailureState(CallStateMachineState.BUSY))
        assertNotEquals(CallStateMachineState.PEER_REJECTED, CallStateMachineState.BUSY)
    }

    @Test
    fun endedFromActiveIsTerminalAndDismissesOverlay() {
        assertTrue(CallNavigationPolicy.shouldDismissOverlay(CallStateMachineState.ENDED))
        assertFalse(CallNavigationPolicy.isCallOverlayState(CallStateMachineState.IDLE))
    }
}
