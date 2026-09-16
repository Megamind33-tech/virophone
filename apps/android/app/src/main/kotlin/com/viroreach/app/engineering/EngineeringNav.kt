package com.viroreach.app.engineering

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.viroreach.app.call.IncomingCallBanner
import com.viroreach.app.diagnostic.DiscoveryDiagnosticScreen
import com.viroreach.app.session.SessionManager
import com.viroreach.core.model.CallStateMachineState
import com.viroreach.feature.calling.CallManager
import com.viroreach.feature.calling.ResolvedCallTarget
import com.viroreach.feature.calling.SignalingConnectionState
import kotlinx.coroutines.launch

enum class EngineeringTab { AUTH, CALL, DIAGNOSTIC }

@Composable
fun EngineeringNav() {
    val context = LocalContext.current
    val session = remember { SessionManager.get(context) }
    var tab by remember { mutableStateOf(EngineeringTab.AUTH) }

    Scaffold(
        topBar = { EngineeringStatusHeader(session) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == EngineeringTab.AUTH,
                    onClick = { tab = EngineeringTab.AUTH },
                    label = { Text("Auth") },
                    icon = {},
                )
                NavigationBarItem(
                    selected = tab == EngineeringTab.CALL,
                    onClick = { tab = EngineeringTab.CALL },
                    label = { Text("Call") },
                    icon = {},
                )
                NavigationBarItem(
                    selected = tab == EngineeringTab.DIAGNOSTIC,
                    onClick = { tab = EngineeringTab.DIAGNOSTIC },
                    label = { Text("Diag") },
                    icon = {},
                )
            }
        },
    ) { padding ->
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        var pendingIncomingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        val micPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) pendingIncomingAction?.invoke() else pendingIncomingAction = null
        }
        fun withMicForIncoming(action: () -> Unit) {
            when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
                PackageManager.PERMISSION_GRANTED -> action()
                else -> {
                    pendingIncomingAction = action
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
        Column(Modifier.padding(padding)) {
            IncomingCallBanner(
                session = session,
                onAccept = {
                    withMicForIncoming {
                        scope.launch {
                            val id = session.callManager.incomingCall.value?.callId
                                ?: session.callManager.pendingCallId()
                                ?: return@launch
                            session.callManager.acceptCall(id)
                        }
                    }
                },
                onReject = {
                    scope.launch { session.callManager.rejectCall() }
                },
            )
            Box {
                when (tab) {
                    EngineeringTab.AUTH -> AuthEngineeringScreen(session)
                    EngineeringTab.CALL -> CallEngineeringScreen(session)
                    EngineeringTab.DIAGNOSTIC -> DiscoveryDiagnosticScreen(session)
                }
            }
        }
    }
}

@Composable
fun CallEngineeringScreen(session: EngineeringSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callManager = session.callManager

    var targetPhone by remember { mutableStateOf(session.lastTargetInput) }
    LaunchedEffect(targetPhone) {
        session.updateLastTargetInput(targetPhone)
    }
    var relayLabel by remember { mutableStateOf("AUTO") }
    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var resolving by remember { mutableStateOf(false) }

    val state by callManager.state.collectAsState()
    val route by callManager.routeLabel.collectAsState()
    val ice by callManager.iceSummary.collectAsState()
    val quality by callManager.callQuality.collectAsState()
    val lastError by callManager.lastError.collectAsState()
    val wssState by callManager.wssConnectionState.collectAsState()
    val wssDetail by callManager.wssStatusDetail.collectAsState()
    val resolved by callManager.resolvedTarget.collectAsState()
    val prerequisites = callManager.internetCallPrerequisites()
    val targetReady = resolved != null &&
        (resolved?.inputIdentity == targetPhone.trim() || resolved?.phoneE164 == targetPhone.trim())
    val canStartCall = prerequisites.isEmpty() && targetReady &&
        wssState == SignalingConnectionState.CONNECTED

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) pendingCallAction?.invoke() else pendingCallAction = null
    }

    fun withMicPermission(action: () -> Unit) {
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
            PackageManager.PERMISSION_GRANTED -> action()
            else -> {
                pendingCallAction = action
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Column(
        Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Phase 1B — Resilient Voice Engine", style = MaterialTheme.typography.titleMedium)
        Text(
            "Callee: stay AUTH + WSS connected — no target resolution needed.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("State: $state")
        Text("Route: $route")
        Text("ICE: $ice")
        quality?.let { q ->
            Text("Quality: RTT ${q.roundTripMs.toInt()}ms · jitter ${q.jitterMs.toInt()}ms · loss ${"%.1f".format(q.packetLossPercent)}% · ${q.bitrateKbps.toInt()} kbps · ${q.codec}")
            Text("Data: sent ${q.bytesSent} B · received ${q.bytesReceived} B")
        }
        Text("WSS: $wssState — ${wssDetail ?: "—"}")
        if (prerequisites.isNotEmpty()) {
            Text("Missing: ${prerequisites.joinToString(", ")}", color = MaterialTheme.colorScheme.error)
        }
        lastError?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(
            value = targetPhone,
            onValueChange = { targetPhone = it },
            label = { Text("TARGET PHONE (E.164)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !resolving,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        resolving = true
                        callManager.resolveTarget(targetPhone)
                        resolving = false
                    }
                },
                enabled = !resolving && targetPhone.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text(if (resolving) "RESOLVING…" else if (resolved != null) "REFRESH TARGET" else "RESOLVE TARGET") }
            Button(
                onClick = {
                    callManager.clearResolvedTarget()
                },
                enabled = resolved != null,
                modifier = Modifier.weight(1f),
            ) { Text("CLEAR TARGET") }
        }

        ResolvedTargetBlock(resolved)

        WssControlButton(session)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                callManager.relayMode = CallManager.RelayMode.AUTO
                relayLabel = "AUTO"
            }) { Text("ICE AUTO") }
            Button(onClick = {
                callManager.relayMode = CallManager.RelayMode.TURN_UDP
                relayLabel = "TURN UDP"
            }) { Text("Force UDP") }
            Button(onClick = {
                callManager.relayMode = CallManager.RelayMode.TURN_TCP
                relayLabel = "TURN TCP"
            }) { Text("Force TCP") }
        }
        Text("Relay mode: $relayLabel")

        Button(
            onClick = {
                withMicPermission {
                    scope.launch { callManager.startCall(targetPhone) }
                }
            },
            enabled = canStartCall,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start Call") }

        Button(onClick = {
            withMicPermission {
                scope.launch {
                    val id = callManager.pendingCallId() ?: return@launch
                    callManager.acceptCall(id)
                }
            }
        }) { Text("Accept Call") }

        Button(onClick = { scope.launch { callManager.hangUp() } }) { Text("Hang Up") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { callManager.setMuted(true) }) { Text("Mute") }
            Button(onClick = { callManager.setMuted(false) }) { Text("Unmute") }
            Button(onClick = { callManager.setSpeaker(true) }) { Text("Speaker") }
        }
        Button(onClick = { callManager.refreshIceSummary() }) { Text("Refresh ICE stats") }
    }
}

@Composable
fun WssControlButton(session: EngineeringSession) {
    val scope = rememberCoroutineScope()
    val callManager = session.callManager
    val wssState by callManager.wssConnectionState.collectAsState()
    val wssDetail by callManager.wssStatusDetail.collectAsState()
    val label = session.wssButtonLabel()
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    when (wssState) {
                        SignalingConnectionState.CONNECTED -> callManager.disconnectSignaling()
                        SignalingConnectionState.CONNECTING,
                        SignalingConnectionState.AUTHENTICATING,
                        SignalingConnectionState.RECONNECTING,
                        -> Unit
                        SignalingConnectionState.FAILED ->
                            callManager.connectSignaling(force = true)
                        else -> callManager.connectSignaling(force = false)
                    }
                    busy = false
                }
            },
            enabled = !busy && wssState != SignalingConnectionState.CONNECTING &&
                wssState != SignalingConnectionState.AUTHENTICATING &&
                wssState != SignalingConnectionState.RECONNECTING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "WORKING…" else label) }
        wssDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ResolvedTargetBlock(resolved: ResolvedCallTarget?) {
    if (resolved == null) {
        Text("RESOLVED: NOT RESOLVED", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text("RESOLVED: AUTHORIZED", style = MaterialTheme.typography.bodySmall)
    Text("User: ${resolved.userId.take(8)}…", style = MaterialTheme.typography.bodySmall)
    resolved.phoneE164?.let {
        Text("Phone: $it", style = MaterialTheme.typography.bodySmall)
    }
    resolved.relationshipState?.let {
        Text("Relationship: $it", style = MaterialTheme.typography.bodySmall)
    }
}
