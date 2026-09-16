package com.viroreach.app.engineering

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viroreach.core.model.CallStateMachineState
import com.viroreach.feature.calling.SignalingConnectionState

@Composable
fun EngineeringStatusHeader(session: EngineeringSession) {
    val phone = session.testIdentityStore.getPhoneE164()
        ?: session.tokenStore.getAuthenticatedPhoneE164()
    val authenticated = session.isAuthenticated
    val wssState by session.callManager.wssConnectionState.collectAsState()
    val wssGen by session.callManager.wssGeneration.collectAsState()
    val wssDetail by session.callManager.wssStatusDetail.collectAsState()
    val webRtcReady by session.callManager.webRtcReady.collectAsState()
    val resolved by session.callManager.resolvedTarget.collectAsState()
    val callState by session.callManager.state.collectAsState()
    val networkLabel by session.networkMonitor.networkLabel.collectAsState()
    val internetValidated by session.networkMonitor.internetValidated.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var offlineTrustCount by remember { mutableStateOf(0) }
    LaunchedEffect(authenticated) {
        offlineTrustCount = if (authenticated) {
            com.viroreach.feature.calling.OfflineTrustStore(context).getMaterial().size
        } else {
            0
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Engineering status", style = MaterialTheme.typography.labelMedium)
            Text("PHONE: ${phone ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text(
                "AUTH: ${if (authenticated) "AUTHENTICATED" else "NOT AUTHENTICATED"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "WSS: ${wssState.name} (gen $wssGen)",
                style = MaterialTheme.typography.bodySmall,
            )
            wssDetail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "WEBRTC: ${if (webRtcReady) "READY" else "NOT READY"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "TARGET: ${session.lastTargetInput.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "TARGET AUTH: ${if (resolved != null) "AUTHORIZED" else "UNRESOLVED"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "CALL: ${callState.name}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "NETWORK: $networkLabel${if (internetValidated) " / INTERNET VALIDATED" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "OFFLINE TRUST: ${if (authenticated && offlineTrustCount > 0) "SYNCED ($offlineTrustCount)" else if (authenticated) "SYNCED (0 entries)" else "NOT SYNCED"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
