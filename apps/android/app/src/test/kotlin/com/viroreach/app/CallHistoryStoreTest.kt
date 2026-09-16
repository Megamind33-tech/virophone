package com.viroreach.app

import com.viroreach.app.consumer.data.CallLogType
import com.viroreach.app.consumer.resolveCallLogType
import com.viroreach.core.model.CallStateMachineState
import org.junit.Assert.assertEquals
import org.junit.Test

class CallHistoryStoreTest {
    @Test
    fun unansweredIncomingEnded_mapsToMissed_notFailed() {
        assertEquals(
            CallLogType.MISSED,
            resolveCallLogType(CallStateMachineState.ENDED, 0, null, CallLogType.INCOMING),
        )
    }

    @Test
    fun unansweredOutgoingEnded_staysOutgoing() {
        assertEquals(
            CallLogType.OUTGOING,
            resolveCallLogType(CallStateMachineState.ENDED, 0, null, CallLogType.OUTGOING),
        )
    }

    @Test
    fun answeredCallEnded_mapsToCompleted() {
        assertEquals(
            CallLogType.COMPLETED,
            resolveCallLogType(CallStateMachineState.ENDED, 42, null, CallLogType.OUTGOING),
        )
    }

    @Test
    fun pickedUpCall_mapsToCompleted_evenWithZeroDuration() {
        assertEquals(
            CallLogType.COMPLETED,
            resolveCallLogType(CallStateMachineState.ENDED, 0, 1_700_000_000_000L, CallLogType.INCOMING),
        )
    }

    @Test
    fun mediaFailedBeforeAnswer_incoming_mapsToMissed() {
        assertEquals(
            CallLogType.MISSED,
            resolveCallLogType(CallStateMachineState.MEDIA_FAILED, 0, null, CallLogType.INCOMING),
        )
    }

    @Test
    fun explicitFailedState_outgoing_mapsToFailed() {
        assertEquals(
            CallLogType.FAILED,
            resolveCallLogType(CallStateMachineState.FAILED, 0, null, CallLogType.OUTGOING),
        )
    }
}
