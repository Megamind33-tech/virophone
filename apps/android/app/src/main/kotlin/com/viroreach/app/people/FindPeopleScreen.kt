package com.viroreach.app.people

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroAvatar
import com.viroreach.core.designsystem.components.ViroAvatarSize
import com.viroreach.core.designsystem.components.ViroBackButton
import com.viroreach.core.designsystem.components.ViroErrorMessage
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import com.viroreach.core.network.ConnectionDto
import com.viroreach.core.network.FoundPerson
import kotlinx.coroutines.launch

/** Public page behind a shared Viro ID link. */
fun inviteLink(viroId: String): String =
    "${com.viroreach.core.network.BuildConfig.API_BASE_URL}/api/v1/invite/${viroId.removePrefix("@")}"

/**
 * Reaching someone who isn't in the address book: search their exact Viro ID
 * or the email they signed up with, then ask to connect. Once they accept,
 * both can call and message, and they appear in Contacts.
 */
@Composable
fun FindPeopleScreen(
    session: SessionManager,
    initialQuery: String = "",
    onBack: () -> Unit,
    onMessage: (userId: String, name: String, avatarUrl: String?) -> Unit,
    onCall: (userId: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by session.people.me.collectAsState()
    val connections by session.people.connections.collectAsState()

    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<FoundPerson?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        session.people.refreshMe()
        session.people.refreshConnections()
        if (initialQuery.isNotBlank()) search(session, initialQuery) { s, p, e -> searching = s; person = p; error = e; searched = true }
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isBlank() || searching) return
        scope.launch {
            searching = true; error = null
            session.people.find(q)
                .onSuccess { person = it; error = null }
                .onFailure { person = null; error = it.message }
            searching = false; searched = true
        }
    }

    fun act(block: suspend () -> Result<*>) {
        if (busy) return
        busy = true
        scope.launch {
            block().onFailure { error = it.message }
            // Reflect the new state without a second search.
            session.people.find(query.trim()).onSuccess { person = it }
            busy = false
        }
    }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(Modifier.fillMaxSize().padding(horizontal = ViroSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViroBackButton(onClick = onBack)
                    Spacer(Modifier.width(ViroSpacing.sm))
                    Text("Add people", style = MaterialTheme.typography.titleLarge, color = ViroColors.textPrimary)
                }
                Spacer(Modifier.height(ViroSpacing.sm))
                Text(
                    "Search someone's Viro ID or the email they signed up with.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ViroColors.textSecondary,
                )
                Spacer(Modifier.height(ViroSpacing.md))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; searched = false },
                    label = { Text("@viro.id or email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Search),
                    trailingIcon = {
                        TextButton(onClick = { runSearch() }, enabled = query.isNotBlank() && !searching) {
                            Text(if (searching) "…" else "Search")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Spacer(Modifier.height(ViroSpacing.md))
                    ViroErrorMessage(it)
                }

                val found = person
                when {
                    searching -> {
                        Spacer(Modifier.height(ViroSpacing.lg))
                        CircularProgressIndicator(color = ViroColors.accent)
                    }
                    found != null -> {
                        Spacer(Modifier.height(ViroSpacing.lg))
                        PersonCard(
                            person = found,
                            busy = busy,
                            onConnect = { act { session.people.connect(found.userId) } },
                            onAccept = { found.connection?.id?.let { id -> act { session.people.accept(id) } } },
                            onCancel = { found.connection?.id?.let { id -> act { session.people.remove(id) } } },
                            onMessage = { onMessage(found.userId, found.displayName ?: found.viroId ?: "Viro user", found.avatarUrl) },
                            onCall = { onCall(found.userId, found.displayName ?: found.viroId ?: "Viro user") },
                        )
                    }
                    searched && error == null -> {
                        Spacer(Modifier.height(ViroSpacing.lg))
                        Text("Nobody found with that Viro ID or email.", color = ViroColors.textSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Check the spelling. People can also hide themselves from email search.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ViroColors.textMuted,
                        )
                    }
                }

                Spacer(Modifier.height(ViroSpacing.xl))
                HorizontalDivider(color = ViroColors.surfaceRaised)
                Spacer(Modifier.height(ViroSpacing.md))
                Text("Your Viro ID", style = MaterialTheme.typography.labelMedium, color = ViroColors.textMuted)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        me?.viroId ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                        color = ViroColors.accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    me?.viroId?.let { id ->
                        TextButton(onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Reach me on Viro: $id\n${inviteLink(id)}")
                            }
                            runCatching { context.startActivity(Intent.createChooser(share, "Share your Viro ID")) }
                        }) {
                            Icon(Icons.Default.Share, null, tint = ViroColors.accent)
                            Spacer(Modifier.width(6.dp))
                            Text("Share", color = ViroColors.accent)
                        }
                    }
                }

                val incoming = connections.filter { it.isIncomingRequest }
                if (incoming.isNotEmpty()) {
                    Spacer(Modifier.height(ViroSpacing.lg))
                    Text("Requests for you", style = MaterialTheme.typography.labelMedium, color = ViroColors.textMuted)
                    incoming.forEach { c ->
                        RequestRow(
                            connection = c,
                            busy = busy,
                            onAccept = { act { session.people.accept(c.id) } },
                            onDecline = { act { session.people.decline(c.id) } },
                        )
                    }
                }
            }
        }
    }
}

private suspend fun search(
    session: SessionManager,
    q: String,
    update: (Boolean, FoundPerson?, String?) -> Unit,
) {
    update(true, null, null)
    session.people.find(q)
        .onSuccess { update(false, it, null) }
        .onFailure { update(false, null, it.message) }
}

@Composable
private fun PersonCard(
    person: FoundPerson,
    busy: Boolean,
    onConnect: () -> Unit,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
    onMessage: () -> Unit,
    onCall: () -> Unit,
) {
    val name = person.displayName?.takeIf { it.isNotBlank() } ?: person.viroId ?: "Viro user"
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ViroAvatar(displayName = name, imageUrl = person.avatarUrl, size = ViroAvatarSize.Large)
            Spacer(Modifier.width(ViroSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(name, color = ViroColors.textPrimary, style = MaterialTheme.typography.titleMedium)
                person.viroId?.let { Text(it, color = ViroColors.textSecondary) }
            }
        }
        Spacer(Modifier.height(ViroSpacing.md))
        val status = person.connection?.status
        val incoming = person.connection?.direction == "INCOMING"
        when {
            status == "ACCEPTED" -> Row(horizontalArrangement = Arrangement.spacedBy(ViroSpacing.sm)) {
                Button(onClick = onMessage, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Chat, null); Spacer(Modifier.width(8.dp)); Text("Message")
                }
                OutlinedButton(onClick = onCall, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text("Call")
                }
            }
            status == "PENDING" && incoming -> Row(horizontalArrangement = Arrangement.spacedBy(ViroSpacing.sm)) {
                Button(onClick = onAccept, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Accept request") }
                OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Ignore") }
            }
            status == "PENDING" -> Column {
                OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Requested — cancel") }
                Spacer(Modifier.height(4.dp))
                Text(
                    "They'll see your request and can accept it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ViroColors.textMuted,
                )
            }
            else -> Column {
                Button(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("Connect")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Once they accept, you can call and message each other.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ViroColors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun RequestRow(connection: ConnectionDto, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    val name = connection.peerDisplayName?.takeIf { it.isNotBlank() } ?: connection.peerViroId ?: "Viro user"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViroAvatar(displayName = name, imageUrl = connection.peerAvatarUrl, size = ViroAvatarSize.Medium)
        Spacer(Modifier.width(ViroSpacing.sm))
        Column(Modifier.weight(1f)) {
            Text(name, color = ViroColors.textPrimary)
            connection.peerViroId?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ViroColors.textSecondary) }
        }
        TextButton(onClick = onAccept, enabled = !busy) { Text("Accept", color = ViroColors.accent) }
        TextButton(onClick = onDecline, enabled = !busy) { Text("Ignore", color = ViroColors.textSecondary) }
    }
}
