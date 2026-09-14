package com.viroreach.app.diagnostic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.viroreach.core.model.*
import com.viroreach.feature.discovery.*
import com.viroreach.transport.lan.LanCallTransport
import com.viroreach.transport.wifidirect.WifiDirectCallTransport
import com.viroreach.transport.internet.InternetSipCallTransport
import kotlinx.coroutines.launch

enum class DiagnosticDataSource {
    REAL,
    SIMULATED,
    STUBBED,
    NOT_IMPLEMENTED,
    BLOCKED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryDiagnosticScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ephemeralGen = remember { EphemeralIdGenerator() }

    var discoveryMode by remember { mutableStateOf(DiagnosticDataSource.NOT_IMPLEMENTED) }

    val stubResolver = remember {
        object : AuthorizedPeerResolver {
            override suspend fun resolve(ephemeralId: String): AuthorizedNearbyContact? = null
        }
    }

    val discoveryService = remember {
        lateinit var service: LocalNetworkDiscoveryService
        val nsd = NsdLanDiscovery(context, ephemeralGen) { eid, transport ->
            service.onPeerDiscovered(eid, transport)
        }
        val wifi = WifiDirectDiscovery(context, ephemeralGen) { eid, transport ->
            service.onPeerDiscovered(eid, transport)
        }
        service = LocalNetworkDiscoveryService(ephemeralGen, stubResolver, nsd, wifi)
        service
    }

    val authorizedMatches by discoveryService.authorizedMatches.collectAsState()
    val anonymousCount by discoveryService.anonymousPeerCount.collectAsState()

    var lanAvailable by remember { mutableStateOf(TransportAvailability.UNAVAILABLE) }
    var wifiDirectAvailable by remember { mutableStateOf(TransportAvailability.UNAVAILABLE) }
    var internetAvailable by remember { mutableStateOf(TransportAvailability.UNAVAILABLE) }
    var nsdRunning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        lanAvailable = LanCallTransport().checkAvailability()
        wifiDirectAvailable = WifiDirectCallTransport().checkAvailability()
        internetAvailable = InternetSipCallTransport().checkAvailability()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Viro Reach — Phase 0.6 Diagnostic") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Engineering diagnostic — not production UI", style = MaterialTheme.typography.titleSmall)
                        Text("Values labeled with data source. No fabricated production state.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Text("Account / Device", style = MaterialTheme.typography.titleMedium)
                StatusLine("ACCOUNT", "NOT AVAILABLE", DiagnosticDataSource.NOT_IMPLEMENTED)
                StatusLine("DEVICE / Keystore", "NOT AVAILABLE", DiagnosticDataSource.BLOCKED)
            }

            item {
                Text("Server / Signaling", style = MaterialTheme.typography.titleMedium)
                StatusLine("SERVER", "NOT AVAILABLE", DiagnosticDataSource.NOT_IMPLEMENTED)
                StatusLine("WEBSOCKET", "NOT IMPLEMENTED", DiagnosticDataSource.NOT_IMPLEMENTED)
                StatusLine("VOICE ENGINE", "WebRTC (module built)", DiagnosticDataSource.REAL)
            }

            item {
                Text("Transport", style = MaterialTheme.typography.titleMedium)
                StatusLine("LAN transport", lanAvailable.name, DiagnosticDataSource.STUBBED)
                StatusLine("Wi-Fi Direct", wifiDirectAvailable.name, DiagnosticDataSource.REAL)
                StatusLine("Internet (legacy SIP transport)", internetAvailable.name, DiagnosticDataSource.STUBBED)
            }

            item {
                HorizontalDivider()
                Text("Local Discovery", style = MaterialTheme.typography.titleMedium)
                StatusLine("Ephemeral ID", ephemeralGen.getCurrentId(), DiagnosticDataSource.REAL)
                StatusLine("LAN NSD", if (nsdRunning) "Running" else "Stopped", if (nsdRunning) DiagnosticDataSource.REAL else DiagnosticDataSource.NOT_IMPLEMENTED)
                StatusLine("Discovery mode", discoveryMode.name, discoveryMode)
                StatusLine("Anonymous peers", anonymousCount.toString(), discoveryMode)
                StatusLine("Authorized local contacts", authorizedMatches.size.toString(), discoveryMode)
            }

            if (authorizedMatches.isNotEmpty()) {
                item { Text("Authorized Matches", style = MaterialTheme.typography.titleSmall) }
                items(authorizedMatches) { match ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(match.contact.localName, style = MaterialTheme.typography.bodyLarge)
                            Text("${match.transportType.name} — ${discoveryMode.name}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        discoveryService.startLanDiscovery()
                        discoveryService.startWifiDirectDiscovery()
                        nsdRunning = true
                        discoveryMode = DiagnosticDataSource.REAL
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start REAL NSD + Wi-Fi Direct discovery") }
            }

            item {
                Button(
                    onClick = {
                        scope.launch {
                            discoveryMode = DiagnosticDataSource.SIMULATED
                            discoveryService.clear()
                            listOf(
                                "vr1_SimPeer00000000000001",
                                "vr1_SimPeer00000000000002",
                                "vr1_SimPeer00000000000003",
                            ).forEach { id ->
                                discoveryService.onPeerDiscovered(id, CallRouteType.LAN)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Run SIMULATED privacy test (3 anonymous peers)") }
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
                            discoveryMode = DiagnosticDataSource.NOT_IMPLEMENTED
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop discovery & rotate ephemeral ID") }
            }

            item {
                Text("Call / ICE", style = MaterialTheme.typography.titleMedium)
                StatusLine("ICE", "NOT AVAILABLE", DiagnosticDataSource.NOT_IMPLEMENTED)
                StatusLine("ROUTE", "NOT AVAILABLE", DiagnosticDataSource.NOT_IMPLEMENTED)
                StatusLine("RTT / PACKET LOSS", "NOT AVAILABLE", DiagnosticDataSource.NOT_IMPLEMENTED)
            }
        }
    }
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
