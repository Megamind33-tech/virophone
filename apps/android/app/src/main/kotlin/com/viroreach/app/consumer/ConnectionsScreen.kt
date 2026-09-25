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
import com.viroreach.core.designsystem.components.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.viroreach.core.designsystem.ViroSpacing
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

    val act: (suspend () -> Unit) -> Unit = { call ->
        scope.launch {
            runCatching { call() }
            reload()
        }
    }

    ViroSubScreen(title = "Connections", onBack = onBack) {
        when {
            loading -> ViroLoadingState()
            error != null -> ViroMessageState(
                title = "Connections couldn't load",
                body = "Check your connection and try again.",
                actionLabel = "Try again",
                onAction = { scope.launch { reload() } },
            )
            connections.isEmpty() -> ViroMessageState(
                title = "No connections yet",
                body = "Use Add people to find someone by their Viro ID or email — useful when they have no phone number on Viro.",
                icon = Icons.Outlined.People,
            )
            else -> {
                if (incoming.isNotEmpty()) {
                    ViroSection(title = "Requests · ${incoming.size}") {
                        incoming.forEachIndexed { index, conn ->
                            if (index > 0) ViroRowDivider()
                            ConnectionRow(peerLabel(conn), "Wants to connect", conn.peerAvatarUrl) {
                                TextButton(onClick = { act { session.api.rejectConnection(conn.id) } }) {
                                    Text("Decline", color = ViroColors.textSecondary)
                                }
                                TextButton(onClick = { act { session.api.acceptConnection(conn.id) } }) {
                                    Text("Accept", color = ViroColors.accent)
                                }
                            }
                        }
                    }
                }
                if (outgoing.isNotEmpty()) {
                    ViroSection(title = "Sent") {
                        outgoing.forEachIndexed { index, conn ->
                            if (index > 0) ViroRowDivider()
                            ConnectionRow(peerLabel(conn), "Waiting for them to accept", conn.peerAvatarUrl) {
                                TextButton(onClick = { act { session.api.revokeConnection(conn.id) } }) {
                                    Text("Cancel", color = ViroColors.textSecondary)
                                }
                            }
                        }
                    }
                }
                if (accepted.isNotEmpty()) {
                    ViroSection(title = "Connected · ${accepted.size}") {
                        accepted.forEachIndexed { index, conn ->
                            if (index > 0) ViroRowDivider()
                            ConnectionRow(peerLabel(conn), "Connected", conn.peerAvatarUrl) {
                                TextButton(onClick = { act { session.api.revokeConnection(conn.id) } }) {
                                    Text("Remove", color = ViroColors.consumerError)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    title: String,
    subtitle: String,
    avatarUrl: String?,
    actions: @Composable RowScope.() -> Unit,
) {
    ViroListRow(
        title = title,
        subtitle = subtitle,
        leading = { ViroAvatar(size = ViroAvatarSize.Small, imageUrl = avatarUrl, displayName = title) },
        trailing = { Row(verticalAlignment = Alignment.CenterVertically, content = actions) },
    )
}

/**
 * The other person's name. A Viro ID (an internal identifier) is never shown
 * as a name — their handle, or a neutral label, stands in until the server
 * sends the profile.
 */
private fun peerLabel(conn: ConnectionDto): String =
    conn.peerDisplayName?.takeIf { it.isNotBlank() }
        ?: conn.peerViroId?.takeIf { it.isNotBlank() }
        ?: "Viro user"
