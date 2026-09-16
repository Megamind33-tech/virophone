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
}
