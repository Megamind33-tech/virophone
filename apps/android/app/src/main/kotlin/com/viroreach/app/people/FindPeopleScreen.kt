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
import com.viroreach.core.designsystem.components.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Search
import com.viroreach.core.designsystem.ViroSpacing
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

    val shareId: (String) -> Unit = { id ->
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Reach me on Viro: $id\n${inviteLink(id)}")
        }
        runCatching { context.startActivity(Intent.createChooser(share, "Share your Viro ID")) }
    }

    ViroSubScreen(title = "Add people", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ViroTextField(
                value = query,
                onValueChange = { query = it; searched = false },
                placeholder = "@viro.id or email",
                leadingIcon = Icons.Default.Search,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                trailing = {
                    if (searching) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = ViroColors.accent, modifier = Modifier.size(20.dp))
                    } else if (query.isNotBlank()) {
                        IconButton(onClick = { runSearch() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Search", tint = ViroColors.accent)
                        }
                    }
                },
            )
            ViroFootnote("Search someone's Viro ID or the email they signed up with.")
        }

        error?.let { ViroStatusLine(it) }

        val found = person
        when {
            found != null -> PersonCard(
                person = found,
                busy = busy,
                onConnect = { act { session.people.connect(found.userId) } },
                onAccept = { found.connection?.id?.let { id -> act { session.people.accept(id) } } },
                onCancel = { found.connection?.id?.let { id -> act { session.people.remove(id) } } },
                onMessage = { onMessage(found.userId, found.displayName ?: found.viroId ?: "Viro user", found.avatarUrl) },
                onCall = { onCall(found.userId, found.displayName ?: found.viroId ?: "Viro user") },
            )
            searched && !searching && error == null -> ViroMessageState(
                title = "Nobody found",
                body = "Check the spelling. People can also hide themselves from email search.",
                icon = Icons.Default.PersonSearch,
            )
        }

        ViroSection(title = "Your Viro ID", footer = "Share it so people can find you without your number.") {
            ViroListRow(
                title = me?.viroId ?: "Not set yet",
                icon = Icons.Default.AlternateEmail,
                onClick = me?.viroId?.let { id -> { shareId(id) } },
                trailing = me?.viroId?.let {
                    { Icon(Icons.Default.Share, contentDescription = "Share your Viro ID", tint = ViroColors.accent, modifier = Modifier.size(20.dp)) }
                },
            )
        }

        val incoming = connections.filter { it.isIncomingRequest }
        if (incoming.isNotEmpty()) {
            ViroSection(title = "Requests for you") {
                incoming.forEachIndexed { index, c ->
                    if (index > 0) ViroRowDivider()
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
    val status = person.connection?.status
    val incoming = person.connection?.direction == "INCOMING"
    Surface(shape = ViroSectionShape, color = viroGroupedSurface(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ViroAvatar(displayName = name, imageUrl = person.avatarUrl, size = ViroAvatarSize.Large)
                Spacer(Modifier.width(ViroSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(name, color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
                    person.viroId?.let { Text(it, color = ViroColors.textSecondary, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            when {
                status == "ACCEPTED" -> Row(horizontalArrangement = Arrangement.spacedBy(ViroSpacing.sm)) {
                    ViroActionButton("Message", onMessage, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.Chat)
                    ViroQuietButton("Call", onCall, Modifier.weight(1f), icon = Icons.Default.Call)
                }
                status == "PENDING" && incoming -> Row(horizontalArrangement = Arrangement.spacedBy(ViroSpacing.sm)) {
                    ViroActionButton("Accept", onAccept, Modifier.weight(1f), enabled = !busy)
                    ViroQuietButton("Ignore", onCancel, Modifier.weight(1f), enabled = !busy)
                }
                status == "PENDING" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ViroQuietButton("Cancel request", onCancel, enabled = !busy)
                    Text("They'll see your request and can accept it.", style = MaterialTheme.typography.bodySmall, color = ViroColors.textSecondary)
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ViroActionButton("Connect", onConnect, enabled = !busy, busy = busy, icon = Icons.Default.PersonAdd)
                    Text("Once they accept, you can call and message each other.", style = MaterialTheme.typography.bodySmall, color = ViroColors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun RequestRow(connection: ConnectionDto, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    val name = connection.peerDisplayName?.takeIf { it.isNotBlank() } ?: connection.peerViroId ?: "Viro user"
    ViroListRow(
        title = name,
        subtitle = connection.peerViroId,
        leading = { ViroAvatar(displayName = name, imageUrl = connection.peerAvatarUrl, size = ViroAvatarSize.Small) },
        trailing = {
            Row {
                TextButton(onClick = onDecline, enabled = !busy) { Text("Ignore", color = ViroColors.textSecondary) }
                TextButton(onClick = onAccept, enabled = !busy) { Text("Accept", color = ViroColors.accent) }
            }
        },
    )
}
