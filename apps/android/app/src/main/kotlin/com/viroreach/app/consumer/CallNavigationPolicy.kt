package com.viroreach.app.consumer

import com.viroreach.core.model.CallStateMachineState

/**
 * Pure policy for consumer call overlay — unit-testable without Compose.
 */
object CallNavigationPolicy {
    private val terminalStates = setOf(
        CallStateMachineState.ENDED,
        CallStateMachineState.IDLE,
    )

    private val failureStates = setOf(
        CallStateMachineState.FAILED,
        CallStateMachineState.UNAUTHORIZED,
        CallStateMachineState.UNREACHABLE,
        CallStateMachineState.NETWORK_FAILED,
        CallStateMachineState.PEER_REJECTED,
        CallStateMachineState.BUSY,
        CallStateMachineState.TIMEOUT,
        CallStateMachineState.MEDIA_FAILED,
        CallStateMachineState.SERVER_FAILED,
    )

    /** Outgoing call screen should open on user tap — before async work begins. */
    fun userInitiatedCallOpensOverlayImmediately(): Boolean = true

    /** States where the full-screen call UI remains visible. */
    fun isCallOverlayState(state: CallStateMachineState): Boolean = state in setOf(
        CallStateMachineState.RESOLVING_CONTACT,
        CallStateMachineState.AUTHORIZING,
        CallStateMachineState.SELECTING_ROUTE,
        CallStateMachineState.INVITING,
        CallStateMachineState.SIGNALING,
        CallStateMachineState.CONNECTING,
        CallStateMachineState.RINGING,
        CallStateMachineState.ACTIVE,
        CallStateMachineState.RECONNECTING,
        CallStateMachineState.ENDING,
    ) || state in failureStates

    fun isIncomingRinging(state: CallStateMachineState, isCaller: Boolean): Boolean =
        state == CallStateMachineState.RINGING && !isCaller

    fun isOutgoingActive(state: CallStateMachineState, isCaller: Boolean): Boolean =
        isCaller && state !in terminalStates

    fun shouldDismissOverlay(state: CallStateMachineState): Boolean = state in terminalStates

    fun isFailureState(state: CallStateMachineState): Boolean = state in failureStates

    /** WSS must not be recreated — CallManager owns one transport; UI never calls connectSignaling on call start. */
    fun callStartMustNotCreateNewWss(): Boolean = true
}
