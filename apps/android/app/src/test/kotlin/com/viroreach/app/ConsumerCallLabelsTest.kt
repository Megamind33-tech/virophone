package com.viroreach.app

import com.viroreach.app.consumer.consumerCallStateLabel
import com.viroreach.app.consumer.consumerFailureMessage
import com.viroreach.core.model.CallStateMachineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Checkpoint D — verifies the LiveKit-integrated call states surface the
 * right label. Most directly, "Do not allow ACCEPTED to automatically mean
 * CONNECTED": CONNECTING (set the instant Accept is pressed, before LiveKit
 * confirms a real media connection) must never render the same label as
 * ACTIVE (set only once a remote track is actually subscribed).
 */
class ConsumerCallLabelsTest {
    @Test
    fun connectingLabelDiffersFromActiveLabel() {
        val connecting = consumerCallStateLabel(CallStateMachineState.CONNECTING, isCaller = true)
        val active = consumerCallStateLabel(CallStateMachineState.ACTIVE, isCaller = true)
        assertNotEquals(connecting, active)
        assertEquals("Connecting…", connecting)
        assertEquals("In call", active)
    }

    @Test
    fun callingLabelDiffersFromRingingAndConnected() {
        val calling = consumerCallStateLabel(CallStateMachineState.INVITING, isCaller = true)
        val ringing = consumerCallStateLabel(CallStateMachineState.RINGING, isCaller = true)
        val active = consumerCallStateLabel(CallStateMachineState.ACTIVE, isCaller = true)
        assertEquals("Calling…", calling)
        assertEquals("Ringing…", ringing)
        assertEquals("In call", active)
    }

    @Test
    fun outgoingCallNeverShowsIncomingLabel() {
        // The isCaller flag must be true immediately when an outgoing call
        // begins (CallManager.startCall sets it before any suspending work),
        // otherwise IDLE would render "Incoming…" on a call the user placed.
        assertEquals("Calling…", consumerCallStateLabel(CallStateMachineState.IDLE, isCaller = true))
        assertEquals("Incoming…", consumerCallStateLabel(CallStateMachineState.IDLE, isCaller = false))
    }

    @Test
    fun rejectedAndBusyShowDistinctReasons() {
        val rejected = consumerFailureMessage(CallStateMachineState.PEER_REJECTED)
        val busy = consumerFailureMessage(CallStateMachineState.BUSY)
        assertEquals("Call declined", rejected)
        assertEquals("Line busy", busy)
        assertNotEquals(rejected, busy)
    }

    @Test
    fun mediaConnectionFailureShowsDistinctMessage() {
        // LiveKit RoomEvent.FailedToConnect -> MEDIA_FAILED must not be
        // reported as if the call simply ended normally.
        val mediaFailed = consumerFailureMessage(CallStateMachineState.MEDIA_FAILED)
        assertEquals("Connection lost", mediaFailed)
    }
}
