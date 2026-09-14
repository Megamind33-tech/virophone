package com.viroreach.app.diagnostic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viroreach.core.model.*
import com.viroreach.feature.discovery.EphemeralIdGenerator
import com.viroreach.feature.discovery.LocalNetworkDiscoveryService
import com.viroreach.feature.discovery.AuthorizedPeerResolver
import com.viroreach.transport.lan.LanCallTransport
import com.viroreach.transport.wifidirect.WifiDirectCallTransport
import com.viroreach.transport.internet.InternetSipCallTransport
import kotlinx.coroutines.launch

/** Local discovery mode — NSD not implemented in Phase 0. */
enum class DiscoveryMode {
    /** No peers — real NSD not wired */
    REAL_NOT_IMPLEMENTED,
    /** Manual injection for privacy logic testing only */
    SIMULATED
}

/**
 * Internal development diagnostic screen — NOT production design.
 * Local peer discovery is SIMULATED until NSD/mDNS is implemented.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryDiagnosticScreen() {
    val scope = rememberCoroutineScope()
    val ephemeralGen = remember { EphemeralIdGenerator() }

    var discoveryMode by remember { mutableStateOf(DiscoveryMode.REAL_NOT_IMPLEMENTED) }

    val stubResolver = remember {
        object : AuthorizedPeerResolver {
            override suspend fun resolve(ephemeralId: String): AuthorizedNearbyContact? = null
        }
    }

    val discoveryService = remember { LocalNetworkDiscoveryService(ephemeralGen, stubResolver) }
    val authorizedMatches by discoveryService.authorizedMatches.collectAsState()

    var lanAvailable by remember { mutableStateOf(TransportAvailability.UNAVAILABLE) }
    var wifiDirectAvailable by remember { mutableStateOf(TransportAvailability.UNAVAILABLE) }
    var internetAvailable by remember { mutableStateOf(TransportAvailability.UNAVAILABLE) }
    var peerCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        lanAvailable = LanCallTransport().checkAvailability()
        wifiDirectAvailable = WifiDirectCallTransport().checkAvailability()
        internetAvailable = InternetSipCallTransport().checkAvailability()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Viro Reach — Phase 0 Diagnostic") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Discovery mode: ${discoveryMode.name}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "NSD/mDNS: NOT IMPLEMENTED. Wi-Fi Direct SD: STUBBED. " +
                                "Peer counts below are SIMULATED only when you tap the button.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Text("Transport Status", style = MaterialTheme.typography.titleMedium)
                StatusRow("Local transport (LAN)", lanAvailable, "STUBBED")
                StatusRow("Wi-Fi Direct", wifiDirectAvailable, "STUBBED")
                StatusRow("Internet", internetAvailable, "STUBBED")
            }

            item {
                HorizontalDivider()
                Text("Local Discovery", style = MaterialTheme.typography.titleMedium)
                Text("Ephemeral ID: ${ephemeralGen.getCurrentId()}")
                Text("Detected anonymous Viro services: $peerCount (${discoveryMode.name})")
                Text("Authorized contact matches: ${authorizedMatches.size}")
            }

            if (authorizedMatches.isNotEmpty()) {
                item { Text("Authorized Matches", style = MaterialTheme.typography.titleSmall) }
                items(authorizedMatches) { match ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(match.contact.localName, style = MaterialTheme.typography.bodyLarge)
                            Text("${match.transportType.name} reachable", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        scope.launch {
                            discoveryMode = DiscoveryMode.SIMULATED
                            discoveryService.clear()
                            listOf(
                                "vr-eph-sim001", "vr-eph-sim002", "vr-eph-sim003",
                                "vr-eph-sim004", "vr-eph-sim005", "vr-eph-sim006", "vr-eph-sim007"
                            ).forEach { id ->
                                discoveryService.onPeerDiscovered(id, CallRouteType.LAN)
                            }
                            peerCount = discoveryService.getAnonymousPeerCount()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Run SIMULATED discovery (7 peers, 0 authorized)")
                }
            }

            item {
                Button(
                    onClick = {
                        scope.launch {
                            ephemeralGen.rotate()
                            discoveryService.clear()
                            peerCount = 0
                            discoveryMode = DiscoveryMode.REAL_NOT_IMPLEMENTED
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Rotate Ephemeral ID & Reset")
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, status: TransportAvailability, implementation: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(label)
            Text(implementation, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            status.name,
            color = if (status == TransportAvailability.AVAILABLE)
                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}
