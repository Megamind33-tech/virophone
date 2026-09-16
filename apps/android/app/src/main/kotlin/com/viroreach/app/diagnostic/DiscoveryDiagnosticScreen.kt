package com.viroreach.app.diagnostic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.viroreach.app.BuildConfig
import com.viroreach.core.model.AuthorizedNearbyContact
import com.viroreach.core.network.ApiDiagnostics
import com.viroreach.core.network.BuildConfig as NetworkBuildConfig
import com.viroreach.core.network.TokenStore
import com.viroreach.app.engineering.EngineeringSession
import com.viroreach.app.engineering.WssControlButton
import com.viroreach.feature.calling.CallManager
import com.viroreach.feature.calling.OfflineTrustStore
import com.viroreach.feature.calling.SignalingConnectionState
import com.viroreach.feature.calling.SignalingRoute
import com.viroreach.feature.discovery.*
import com.viroreach.voice.webrtc.WebRtcEngineState
import com.viroreach.voice.webrtc.WebRtcRuntimeProbe
import kotlinx.coroutines.launch

enum class DiagnosticDataSource {
    REAL,
    SIMULATED,
    STUBBED,
    NOT_IMPLEMENTED,
    BLOCKED,
    PERMISSION_REQUIRED,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryDiagnosticScreen(session: EngineeringSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callManager = session.callManager
    val tokenStore = session.tokenStore
    val ephemeralGen = remember { EphemeralIdGenerator() }
    val offlineTrust = remember { OfflineTrustStore(context) }

    val trustResolver = remember {
        object : AuthorizedPeerResolver {
            override suspend fun resolve(ephemeralId: String, bindingTag: String?): AuthorizedNearbyContact? {
                val deviceId = tokenStore.getDeviceId()
                val peerUserId = offlineTrust.resolvePeerByBindingTag(ephemeralId, bindingTag ?: return null, deviceId)
                    ?: return null
                return AuthorizedNearbyContact(
                    ephemeralId = ephemeralId,
                    contact = com.viroreach.core.model.KnownContact(
                        userId = peerUserId,
                        localName = peerUserId.take(8),
                        phoneE164 = null,
                        viroId = null,
                        relationshipState = com.viroreach.core.model.ContactRelationshipState.UNKNOWN,
                    ),
                    transportType = com.viroreach.core.model.CallRouteType.LAN,
                )
            }
        }
    }

    val discoveryService = remember {
        lateinit var service: LocalNetworkDiscoveryService
        val nsd = NsdLanDiscovery(context, ephemeralGen, bindingTagProvider = null) { eid, transport, btag, host, port ->
            service.onPeerDiscovered(eid, transport, btag, host, port)
        }
        val wifi = WifiDirectDiscovery(context, ephemeralGen) { eid, transport, btag ->
            service.onPeerDiscovered(eid, transport, btag)
        }
        service = LocalNetworkDiscoveryService(ephemeralGen, trustResolver, nsd, wifi)
        service
    }

    var serverReachable by remember { mutableStateOf(false) }
    var serverDetail by remember { mutableStateOf("CHECKING...") }
    var authDetail by remember { mutableStateOf("NOT AUTHENTICATED") }
    var webRtcState by remember { mutableStateOf(WebRtcEngineState.NOT_STARTED) }
    var webRtcDetail by remember { mutableStateOf("NOT STARTED") }
    var nsdRunning by remember { mutableStateOf(false) }
    var wifiDirectDetail by remember { mutableStateOf("CHECKING...") }
    var offlineTrustDetail by remember { mutableStateOf("NOT SYNCED") }

    val wssState by callManager.wssConnectionState.collectAsState()
    val localSignalingState by callManager.localSignalingState.collectAsState()
    val signalingRoute by callManager.signalingRoute.collectAsState()
    val iceSummary by callManager.iceSummary.collectAsState()
    val anonymousCount by discoveryService.anonymousPeerCount.collectAsState()
    val authorizedCount by discoveryService.authorizedMatches.collectAsState()

    LaunchedEffect(Unit) {
        val health = ApiDiagnostics.checkHealth()
        serverReachable = health.reachable
        serverDetail = if (health.reachable) {
            "CONNECTED HTTP ${health.httpStatus} ${health.host}"
        } else {
            "FAILED ${health.error ?: "unreachable"}"
        }

        authDetail = if (tokenStore.getAccessToken() != null) {
            "AUTHENTICATED user=${tokenStore.getUserId()?.take(8)}... device=${tokenStore.getDeviceId()?.take(8)}..."
        } else {
            "NOT AUTHENTICATED"
        }

        val trustEntries = offlineTrust.getMaterial()
        offlineTrustDetail = if (trustEntries.isEmpty()) {
            "NOT SYNCED"
        } else {
            val earliest = trustEntries.minOfOrNull { it.expiresAt }?.take(19) ?: "?"
            "SYNCED ${trustEntries.size} entries earliest_exp=$earliest"
        }

        webRtcState = WebRtcRuntimeProbe.ensureInitialized(context)
        webRtcDetail = when (webRtcState) {
            WebRtcEngineState.READY -> "READY"
            WebRtcEngineState.FAILED -> "FAILED ${WebRtcRuntimeProbe.failureReason() ?: ""}"
            WebRtcEngineState.INITIALIZING -> "INITIALIZING"
            WebRtcEngineState.NOT_STARTED -> "NOT STARTED"
        }

        wifiDirectDetail = describeWifiDirectState(context, discoveryService)

        discoveryService.startLanDiscovery()
        nsdRunning = true
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Viro Reach — Phase 1A Diagnostic") })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Engineering diagnostic — runtime probes only", style = MaterialTheme.typography.titleSmall)
                        Text("No hardcoded PASS state.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Text("BUILD", style = MaterialTheme.typography.titleMedium)
                StatusLine("Commit", BuildConfig.GIT_COMMIT, DiagnosticDataSource.REAL)
                StatusLine("Version", BuildConfig.VERSION_NAME, DiagnosticDataSource.REAL)
                StatusLine("Build type", BuildConfig.BUILD_TYPE, DiagnosticDataSource.REAL)
                StatusLine("API", NetworkBuildConfig.API_BASE_URL.removePrefix("https://"), DiagnosticDataSource.REAL)
                StatusLine("WSS", NetworkBuildConfig.WSS_URL.removePrefix("wss://"), DiagnosticDataSource.REAL)
            }

            item {
                Text("SERVER / AUTH", style = MaterialTheme.typography.titleMedium)
                StatusLine(
                    "SERVER",
                    serverDetail,
                    if (serverReachable) DiagnosticDataSource.REAL else DiagnosticDataSource.BLOCKED,
                )
                StatusLine("AUTH", authDetail, DiagnosticDataSource.REAL)
            }

            item {
                Text("WEBSOCKET / VOICE", style = MaterialTheme.typography.titleMedium)
                StatusLine("WEBSOCKET", wssState.name, DiagnosticDataSource.REAL)
                StatusLine(
                    "WEBRTC ENGINE",
                    webRtcDetail,
                    when (webRtcState) {
                        WebRtcEngineState.READY -> DiagnosticDataSource.REAL
                        WebRtcEngineState.FAILED -> DiagnosticDataSource.BLOCKED
                        else -> DiagnosticDataSource.REAL
                    },
                )
                StatusLine("INTERNET TRANSPORT", "WebRTC (signaling + ICE)", DiagnosticDataSource.REAL)
            }

            item {
                Text("SIGNALING / MEDIA ROUTE", style = MaterialTheme.typography.titleMedium)
                StatusLine(
                    "SIGNALING ROUTE",
                    formatSignalingRoute(signalingRoute),
                    DiagnosticDataSource.REAL,
                )
                StatusLine(
                    "LOCAL SIGNALING",
                    localSignalingState.name,
                    localSignalingSource(localSignalingState),
                )
                StatusLine(
                    "MEDIA ROUTE",
                    formatMediaRoute(iceSummary),
                    DiagnosticDataSource.REAL,
                )
            }

            item {
                Text("LAN / WI-FI DIRECT", style = MaterialTheme.typography.titleMedium)
                StatusLine(
                    "LAN NSD",
                    if (nsdRunning) "RUNNING" else "STOPPED",
                    if (nsdRunning) DiagnosticDataSource.REAL else DiagnosticDataSource.BLOCKED,
                )
                StatusLine("Anonymous peers", anonymousCount.toString(), DiagnosticDataSource.REAL)
                StatusLine("Authorized peers", authorizedCount.size.toString(), DiagnosticDataSource.REAL)
                StatusLine("WI-FI DIRECT", wifiDirectDetail, DiagnosticDataSource.REAL)
            }

            item {
                Text("OFFLINE TRUST", style = MaterialTheme.typography.titleMedium)
                StatusLine("OFFLINE TRUST", offlineTrustDetail, DiagnosticDataSource.REAL)
                StatusLine("Ephemeral ID", ephemeralGen.getCurrentId(), DiagnosticDataSource.REAL)
            }

            item {
                WssControlButton(session)
            }

            item {
                Button(
                    onClick = {
                        discoveryService.startLanDiscovery()
                        discoveryService.startWifiDirectDiscovery()
                        nsdRunning = true
                        wifiDirectDetail = describeWifiDirectState(context, discoveryService)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restart LAN NSD + Wi-Fi Direct discovery") }
            }

            item {
                Button(
                    onClick = {
                        scope.launch {
                            discoveryService.stopLanDiscovery()
                            discoveryService.stopWifiDirectDiscovery()
                            ephemeralGen.rotate()
                            discoveryService.clear()
                            nsdRunning = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop discovery & rotate ephemeral ID") }
            }
        }
    }
}

private fun formatSignalingRoute(route: SignalingRoute): String = when (route) {
    SignalingRoute.NONE -> "NONE"
    SignalingRoute.WSS -> "WSS"
    SignalingRoute.LOCAL_LAN -> "LOCAL_LAN"
    SignalingRoute.WIFI_DIRECT -> "WIFI_DIRECT"
    SignalingRoute.VIRO_PRIVATE -> "VIRO_PRIVATE"
}

private fun localSignalingSource(state: SignalingConnectionState): DiagnosticDataSource = when (state) {
    SignalingConnectionState.STOPPED, SignalingConnectionState.FAILED -> DiagnosticDataSource.BLOCKED
    SignalingConnectionState.LISTENING,
    SignalingConnectionState.CONNECTING,
    SignalingConnectionState.AUTHENTICATING,
    SignalingConnectionState.CONNECTED,
    SignalingConnectionState.RECONNECTING,
    SignalingConnectionState.DISCONNECTED,
    -> DiagnosticDataSource.REAL
}

private fun formatMediaRoute(iceSummary: String): String {
    if (iceSummary == "NOT AVAILABLE" || iceSummary == "NO PEERCONNECTION" || iceSummary == "NO NOMINATED PAIR") {
        return "NONE"
    }
    val lower = iceSummary.lowercase()
    return when {
        lower.contains("relay") -> "RELAY"
        lower.contains("srflx") -> "SRFLX"
        lower.contains("host") -> "HOST"
        else -> iceSummary
    }
}

private fun describeWifiDirectState(context: Context, discovery: LocalNetworkDiscoveryService): String {
    val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    if (manager == null) return "HARDWARE_UNSUPPORTED (no WifiP2pManager)"
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    if (wifi?.isWifiEnabled == false) return "WIFI_DISABLED"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val nearby = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        if (nearby != PackageManager.PERMISSION_GRANTED) {
            return "PERMISSION_MISSING (NEARBY_WIFI_DEVICES)"
        }
    } else {
        val loc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        if (loc != PackageManager.PERMISSION_GRANTED) {
            return "PERMISSION_MISSING (ACCESS_FINE_LOCATION)"
        }
    }
    if (!discovery.isWifiDirectAvailable()) return "CHANNEL_INIT_FAILED"
    return "API_READY (discovery ${if (discovery.isWifiDirectAvailable()) "available" else "unavailable"})"
}

@Composable
private fun StatusLine(label: String, value: String, source: DiagnosticDataSource) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Text(source.name, style = MaterialTheme.typography.labelSmall)
    }
}
