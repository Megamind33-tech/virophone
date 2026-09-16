package com.viroreach.app.consumer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroBackButton
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import com.viroreach.core.network.ConnectionDto
import kotlinx.coroutines.launch

@Composable
fun ConnectionsScreen(
    session: SessionManager,
    onBack: () -> Unit,
) {
    var connections by remember { mutableStateOf<List<ConnectionDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val myUserId = session.tokenStore.getUserId()

    suspend fun reload() {
        loading = true
        runCatching { session.api.listConnections() }
            .onSuccess { connections = it; error = null }
            .onFailure { error = "Couldn't load connections" }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    val incoming = connections.filter { it.status == "PENDING" && it.direction == "INCOMING" }
    val outgoing = connections.filter { it.status == "PENDING" && it.direction == "OUTGOING" }
    val accepted = connections.filter { it.status == "ACCEPTED" }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(Modifier.fillMaxSize().padding(ViroSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViroBackButton(onClick = onBack)
                    Spacer(Modifier.width(ViroSpacing.sm))
                    Text(
                        "Connections",
                        style = MaterialTheme.typography.titleLarge,
                        color = ViroColors.textPrimary,
                    )
                }
                Spacer(Modifier.height(ViroSpacing.md))
                when {
                    loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ViroColors.accent)
                    }
                    error != null -> Text(error!!, color = ViroColors.textSecondary)
                    connections.isEmpty() -> Text(
                        "No connection requests yet. Invite someone from their contact page.",
                        color = ViroColors.textSecondary,
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(ViroSpacing.md)) {
                        if (incoming.isNotEmpty()) {
                            item {
                                Text(
                                    "Incoming requests",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ViroColors.textMuted,
                                )
                            }
                            items(incoming, key = { it.id }) { conn ->
                                ConnectionCard(
                                    title = peerLabel(conn, myUserId),
                                    subtitle = "Wants to connect",
                                    actions = {
                                        TextButton(onClick = {
                                            scope.launch {
                                                runCatching { session.api.acceptConnection(conn.id) }
                                                reload()
                                            }
                                        }) { Text("Accept", color = ViroColors.accent) }
                                        TextButton(onClick = {
                                            scope.launch {
                                                runCatching { session.api.rejectConnection(conn.id) }
                                                reload()
                                            }
                                        }) { Text("Reject", color = ViroColors.textSecondary) }
                                    },
                                )
                            }
                        }
                        if (outgoing.isNotEmpty()) {
                            item {
                                Text(
                                    "Sent requests",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ViroColors.textMuted,
                                )
                            }
                            items(outgoing, key = { it.id }) { conn ->
                                ConnectionCard(
                                    title = peerLabel(conn, myUserId),
                                    subtitle = "Pending",
                                    actions = {
                                        TextButton(onClick = {
                                            scope.launch {
                                                runCatching { session.api.revokeConnection(conn.id) }
                                                reload()
                                            }
                                        }) { Text("Cancel", color = ViroColors.textSecondary) }
                                    },
                                )
                            }
                        }
                        if (accepted.isNotEmpty()) {
                            item {
                                Text(
                                    "Connected",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ViroColors.textMuted,
                                )
                            }
                            items(accepted, key = { it.id }) { conn ->
                                ConnectionCard(
                                    title = peerLabel(conn, myUserId),
                                    subtitle = "Connected",
                                    actions = {
                                        TextButton(onClick = {
                                            scope.launch {
                                                runCatching { session.api.revokeConnection(conn.id) }
                                                reload()
                                            }
                                        }) { Text("Remove", color = ViroColors.consumerError) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    title: String,
    subtitle: String,
    actions: @Composable RowScope.() -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = ViroColors.surface)) {
        Column(Modifier.padding(ViroSpacing.md)) {
            Text(title, color = ViroColors.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = ViroColors.textSecondary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(ViroSpacing.sm), content = actions)
        }
    }
}

private fun peerLabel(conn: ConnectionDto, myUserId: String?): String {
    val peer = if (conn.requesterUserId == myUserId) conn.recipientUserId else conn.requesterUserId
    return peer.take(12)
}
