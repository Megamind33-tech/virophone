package com.viroreach.app.engineering

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.viroreach.app.diagnostic.DiscoveryDiagnosticScreen
import com.viroreach.core.network.TokenStore
import com.viroreach.core.network.ViroApiClient
import com.viroreach.core.security.DeviceIdentityManager
import com.viroreach.feature.calling.CallManager
import com.viroreach.feature.calling.OfflineTrustStore
import kotlinx.coroutines.launch

enum class EngineeringTab { AUTH, CALL, DIAGNOSTIC }

@Composable
fun EngineeringNav() {
    var tab by remember { mutableStateOf(EngineeringTab.AUTH) }
    Scaffold(
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
        Box(Modifier.padding(padding)) {
            when (tab) {
                EngineeringTab.AUTH -> AuthEngineeringScreen()
                EngineeringTab.CALL -> CallEngineeringScreen()
                EngineeringTab.DIAGNOSTIC -> DiscoveryDiagnosticScreen()
            }
        }
    }
}

@Composable
fun AuthEngineeringScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { TokenStore(context) }
    val api = remember { ViroApiClient(tokenStore).api }
    val identity = remember { DeviceIdentityManager(context) }

    var phone by remember { mutableStateOf("+260971100001") }
    var status by remember { mutableStateOf("NOT AUTHENTICATED") }
    var fingerprint by remember { mutableStateOf("NOT AVAILABLE") }

    LaunchedEffect(Unit) {
        fingerprint = identity.getPublicKeyBase64().take(24) + "..."
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Phase 1A — Auth / Device", style = MaterialTheme.typography.titleMedium)
        Text("Keystore fingerprint: $fingerprint")
        Text("Status: $status")
        OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone E.164") })
        Button(onClick = {
            scope.launch {
                try {
                    val otp = api.requestOtp(com.viroreach.core.network.OtpRequestBody(phone))
                    val pubkey = identity.getPublicKeyBase64()
                    val verify = api.verifyOtp(
                        com.viroreach.core.network.OtpVerifyBody(
                            otp.challengeId, "123456", pubkey, "ANDROID", "0.1.0-phase1a",
                        ),
                    )
                    tokenStore.saveSession(verify.accessToken, verify.refreshToken, verify.userId, verify.deviceId)
                    status = "Authenticated user=${verify.userId.take(8)}..."
                    val trust = OfflineTrustStore(context)
                    trust.saveMaterial(api.getOfflineTrustMaterial().material)
                } catch (e: Exception) {
                    status = "Error: ${e.message}"
                }
            }
        }) { Text("OTP Login + Sync Offline Trust") }
    }
}

@Composable
fun CallEngineeringScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { TokenStore(context) }
    val callManager = remember { CallManager(context, tokenStore, forceRelay = false) }

    var targetUserId by remember { mutableStateOf("") }
    val state by callManager.state.collectAsState()
    val route by callManager.routeLabel.collectAsState()

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Phase 1A — Voice Call Test", style = MaterialTheme.typography.titleMedium)
        Text("State: $state")
        Text("Route: $route")
        OutlinedTextField(value = targetUserId, onValueChange = { targetUserId = it }, label = { Text("Target User ID") })
        Button(onClick = { scope.launch { callManager.connectSignaling() } }) { Text("Connect WSS") }
        Button(onClick = { scope.launch { callManager.startCall(targetUserId) } }) { Text("Start Call") }
        Button(onClick = { scope.launch { callManager.hangUp() } }) { Text("Hang Up") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { callManager.setMuted(true) }) { Text("Mute") }
            Button(onClick = { callManager.setSpeaker(true) }) { Text("Speaker") }
        }
    }
}
