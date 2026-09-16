package com.viroreach.app.consumer



import com.viroreach.core.model.CallStateMachineState



fun consumerCallStateLabel(state: CallStateMachineState, isCaller: Boolean = true): String = when (state) {

    CallStateMachineState.IDLE -> if (isCaller) "Calling…" else "Incoming…"

    CallStateMachineState.RESOLVING_CONTACT,

    CallStateMachineState.AUTHORIZING,

    CallStateMachineState.SELECTING_ROUTE,

    CallStateMachineState.INVITING,

    -> "Calling…"

    CallStateMachineState.RINGING -> if (isCaller) "Ringing…" else "Incoming call"

    CallStateMachineState.SIGNALING,

    CallStateMachineState.CONNECTING,

    -> "Connecting…"

    CallStateMachineState.ACTIVE -> "In call"

    CallStateMachineState.RECONNECTING -> "Reconnecting…"

    CallStateMachineState.ENDING -> "Ending…"

    CallStateMachineState.ENDED -> "Call ended"

    else -> "Calling…"

}



fun consumerFailureMessage(state: CallStateMachineState): String = when (state) {

    CallStateMachineState.UNAUTHORIZED -> "Call couldn't connect"

    CallStateMachineState.UNREACHABLE -> "Unavailable"

    CallStateMachineState.PEER_REJECTED -> "Call declined"

    CallStateMachineState.BUSY -> "Line busy"

    CallStateMachineState.TIMEOUT -> "No answer"

    CallStateMachineState.MEDIA_FAILED -> "Connection lost"

    CallStateMachineState.NETWORK_FAILED -> "Network unavailable"

    CallStateMachineState.SERVER_FAILED -> "Call couldn't connect"

    CallStateMachineState.FAILED -> "Unable to connect"

    else -> "Call couldn't connect"

}



fun consumerRouteLabel(engineeringLabel: String): String? {

    val lower = engineeringLabel.lowercase()

    return when {

        "lan" in lower -> "DIRECT"

        "mesh" in lower || "wifi" in lower -> "MESH"

        "turn" in lower || "relay" in lower -> "RELAY"

        "p2p" in lower || "internet" in lower || "wss" in lower -> "VIRO"

        engineeringLabel == "NOT AVAILABLE" -> null

        else -> null

    }

}


