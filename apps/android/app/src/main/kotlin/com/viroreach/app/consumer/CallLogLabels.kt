package com.viroreach.app.consumer

import com.viroreach.app.consumer.data.CallLogType
import com.viroreach.core.designsystem.components.ViroCallLogDirection
import com.viroreach.core.model.CallStateMachineState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun resolveCallLogType(
    state: CallStateMachineState,
    durationSeconds: Int,
    answerTimestampMs: Long?,
    originalType: CallLogType? = null,
): CallLogType {
    val wasIncoming = originalType == CallLogType.INCOMING
    val wasAnswered = durationSeconds > 0 || answerTimestampMs != null

    if (wasAnswered) {
        return CallLogType.COMPLETED
    }

    return when (state) {
        CallStateMachineState.PEER_REJECTED ->
            if (wasIncoming) CallLogType.MISSED else CallLogType.DECLINED
        CallStateMachineState.TIMEOUT,
        CallStateMachineState.BUSY,
        -> if (wasIncoming) CallLogType.MISSED else CallLogType.FAILED
        CallStateMachineState.FAILED,
        CallStateMachineState.UNAUTHORIZED,
        CallStateMachineState.UNREACHABLE,
        CallStateMachineState.NETWORK_FAILED,
        CallStateMachineState.MEDIA_FAILED,
        CallStateMachineState.SERVER_FAILED,
        -> if (wasIncoming) CallLogType.MISSED else CallLogType.FAILED
        CallStateMachineState.ENDED,
        CallStateMachineState.IDLE,
        CallStateMachineState.CONNECTING,
        -> when {
            wasIncoming -> CallLogType.MISSED
            originalType == CallLogType.OUTGOING -> CallLogType.OUTGOING
            else -> CallLogType.FAILED
        }
        CallStateMachineState.ACTIVE -> CallLogType.COMPLETED
        else -> originalType ?: CallLogType.OUTGOING
    }
}

fun callLogStatusLabel(type: CallLogType): String = when (type) {
    CallLogType.MISSED -> "Missed"
    CallLogType.DECLINED -> "Declined"
    CallLogType.FAILED -> "Failed"
    CallLogType.COMPLETED -> "Completed"
    CallLogType.GROUP -> "Group call"
    CallLogType.VOICEMAIL -> "Voicemail"
    CallLogType.INCOMING -> "Incoming"
    CallLogType.OUTGOING -> "Outgoing"
}

fun formatCallLogTime(timestampMs: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))

fun callLogIsFailure(type: CallLogType): Boolean =
    type == CallLogType.FAILED || type == CallLogType.MISSED || type == CallLogType.DECLINED

fun CallLogType.toCallLogDirection(): ViroCallLogDirection = when (this) {
    CallLogType.OUTGOING -> ViroCallLogDirection.OUTGOING
    CallLogType.INCOMING -> ViroCallLogDirection.INCOMING
    CallLogType.MISSED,
    CallLogType.DECLINED,
    -> ViroCallLogDirection.MISSED
    CallLogType.FAILED -> ViroCallLogDirection.FAILED
    CallLogType.GROUP -> ViroCallLogDirection.GROUP
    CallLogType.VOICEMAIL -> ViroCallLogDirection.INCOMING
    CallLogType.COMPLETED -> ViroCallLogDirection.COMPLETED
}
