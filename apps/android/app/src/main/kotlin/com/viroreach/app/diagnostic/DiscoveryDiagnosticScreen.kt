package com.viroreach.app.diagnostic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
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

/**
 * Internal development diagnostic screen — NOT production design.
 * Proves the privacy model: anonymous peers counted, only authorized contacts shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryDiagnosticScreen() {
    val scope = rememberCoroutineScope()
    val ephemeralGen = remember { EphemeralIdGenerator() }

    val demoResolver = remember {
        object : AuthorizedPeerResolver {
            override suspend fun resolve(ephemeralId: String): AuthorizedNearbyContact? {
                if (ephemeralId == "vr-eph-demo01") {
                    return AuthorizedNearbyContact(
                        contact = KnownContact(
                            userId = "usr_demo",
                            localName = "Brian",
                            phoneE164 = "+260961582985",
                            viroId = "@brian.m",
                            relationshipState = ContactRelationshipState.PHONE_CONTACT,
                            presence = PresenceState.LOCAL_NETWORK,
                            reachability = ReachabilityState.REACHABLE,
                            preferredTransport = CallRouteType.LAN
                        ),
                        ephemeralId = ephemeralId,
                        transportType = CallRouteType.LAN
                    )
                }
                return null
            }
        }
    }

    val discoveryService = remember { LocalNetworkDiscoveryService(ephemeralGen, demoResolver) }
    val authorizedMatches by discoveryService.authorizedMatches.collectAsState()

    var lanAvailable by remember { mutableStateOf(TransportAvailability.UNAVAILABLE) }
    var wifiDirectAvailable by remember { mutableStateOf(TransportAvailability.UNAVAILABLE) }
    var internetAvailable by remember { mutableStateOf(TransportAvailability.UNAVAILABLE) }
    var peerCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val lan = LanCallTransport()
        val wifi = WifiDirectCallTransport()
        val internet = InternetSipCallTransport()
        lanAvailable = lan.checkAvailability()
        wifiDirectAvailable = wifi.checkAvailability()
        internetAvailable = internet.checkAvailability()

        // Simulate 7 anonymous peers, only 1 authorized
        val peerIds = listOf(
            "vr-eph-demo01", "vr-eph-unk001", "vr-eph-unk002",
            "vr-eph-unk003", "vr-eph-unk004", "vr-eph-unk005", "vr-eph-unk006"
        )
        peerIds.forEach { id ->
            discoveryService.onPeerDiscovered(id, CallRouteType.LAN)
        }
        peerCount = discoveryService.getAnonymousPeerCount()
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
                Text("Transport Status", style = MaterialTheme.typography.titleMedium)
                StatusRow("Local transport (LAN)", lanAvailable)
                StatusRow("Wi-Fi Direct", wifiDirectAvailable)
                StatusRow("Internet", internetAvailable)
            }

            item {
                HorizontalDivider()
                Text("Local Discovery", style = MaterialTheme.typography.titleMedium)
                Text("Ephemeral ID: ${ephemeralGen.getCurrentId()}")
                Text("Detected anonymous Viro services: $peerCount")
                Text("Authorized contact matches: ${authorizedMatches.size}")
            }

            if (authorizedMatches.isNotEmpty()) {
                item {
                    Text("Authorized Matches", style = MaterialTheme.typography.titleSmall)
                }
                items(authorizedMatches) { match ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(match.contact.localName, style = MaterialTheme.typography.bodyLarge)
                            Text("${match.transportType.name} reachable",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                Text(
                    "The ${peerCount - authorizedMatches.size} unknown peers are never shown by identity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            item {
                Button(
                    onClick = {
                        scope.launch {
                            ephemeralGen.rotate()
                            discoveryService.clear()
                            peerCount = 0
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
private fun StatusRow(label: String, status: TransportAvailability) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(
            status.name,
            color = if (status == TransportAvailability.AVAILABLE)
                MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    }
}
