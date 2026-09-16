package com.viroreach.app.call

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viroreach.app.session.SessionManager
import com.viroreach.core.model.CallStateMachineState
import com.viroreach.feature.calling.IncomingCallInfo

@Composable
fun IncomingCallBanner(
    session: SessionManager,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val callManager = session.callManager
    val state by callManager.state.collectAsState()
    val incoming by callManager.incomingCall.collectAsState()
    val isCaller by callManager.isCallerRole.collectAsState()
    if (state != CallStateMachineState.RINGING || incoming == null || isCaller) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Incoming call", style = MaterialTheme.typography.titleMedium)
            IncomingCallDetails(incoming)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onReject,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) { Text("Decline") }
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("Accept") }
            }
        }
    }
}

@Composable
private fun IncomingCallDetails(incoming: IncomingCallInfo?) {
    if (incoming == null) return
    incoming.callerPhoneE164?.let {
        Text("From $it", style = MaterialTheme.typography.bodyMedium)
    } ?: incoming.callerUserId?.let {
        Text("Incoming call", style = MaterialTheme.typography.bodyMedium)
    }
}
