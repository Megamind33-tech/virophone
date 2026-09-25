package com.viroreach.app.consumer.messages

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.app.consumer.ContactListItem
import com.viroreach.app.consumer.resolveAvatarUrl
import com.viroreach.app.messaging.ui.GroupAvatar
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.components.*
import com.viroreach.core.designsystem.components.ViroAvatar
import com.viroreach.core.designsystem.components.ViroAvatarSize
import com.viroreach.core.network.MemberDto
import kotlinx.coroutines.launch

/** Contacts who are on Viro, for picking group members. */
@Composable
private fun rememberViroContacts(session: SessionManager): List<ContactListItem> {
    var list by remember { mutableStateOf<List<ContactListItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        list = runCatching { session.contactsRepository.loadContacts() }.getOrDefault(emptyList())
            .filter { it.userId != null && it.userId != session.tokenStore.getUserId() }
            .distinctBy { it.userId }
            .sortedBy { it.effectiveDisplayName.lowercase() }
    }
    return list
}

@Composable
private fun ContactPicker(
    contacts: List<ContactListItem>,
    selected: Set<String>,
    exclude: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var q by remember { mutableStateOf("") }
    Column(modifier) {
        ViroTextField(
            value = q,
            onValueChange = { q = it },
            placeholder = "Search contacts on Viro",
            leadingIcon = Icons.Default.Search,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        val shown = contacts.filter { it.userId !in exclude && (q.isBlank() || it.effectiveDisplayName.contains(q, true)) }
        if (shown.isEmpty()) {
            ViroMessageState(
                title = if (q.isBlank()) "No contacts on Viro yet" else "No matches",
                body = if (q.isBlank()) "People you add from Contacts appear here once they're on Viro." else "None of your contacts on Viro match that.",
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            items(shown, key = { it.userId!! }) { c ->
                val id = c.userId!!
                ViroListRow(
                    title = c.effectiveDisplayName,
                    onClick = { onToggle(id) },
                    leading = { ViroAvatar(displayName = c.effectiveDisplayName, imageUrl = c.resolveAvatarUrl(), size = ViroAvatarSize.Small) },
                    trailing = {
                        Checkbox(
                            checked = id in selected,
                            onCheckedChange = { onToggle(id) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = ViroColors.accent,
                                uncheckedColor = ViroColors.textSecondary,
                                checkmarkColor = ViroColors.onAccent,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun NewGroupScreen(session: SessionManager, onBack: () -> Unit, onCreated: (conversationId: String, title: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts = rememberViroContacts(session)
    var title by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    var creating by remember { mutableStateOf(false) }
    BackHandler { onBack() }
    ViroSubScreen(
        title = "New group",
        onBack = onBack,
        subtitle = if (selected.isEmpty()) "Add a name and pick people" else "${selected.size} selected",
        scrollable = false,
        actions = {
            TextButton(
                enabled = title.isNotBlank() && selected.isNotEmpty() && !creating,
                onClick = {
                    creating = true
                    scope.launch {
                        session.messaging.createGroup(title, selected.toList())
                            .onSuccess { onCreated(it, title.trim()) }
                            .onFailure { Toast.makeText(context, "Couldn't create the group. Try again.", Toast.LENGTH_SHORT).show() }
                        creating = false
                    }
                },
            ) { Text(if (creating) "Creating…" else "Create", color = ViroColors.accent, fontWeight = FontWeight.SemiBold) }
        },
    ) {
        ViroTextField(
            value = title,
            onValueChange = { title = it.take(120) },
            label = "Group name",
            leadingIcon = Icons.Default.Groups,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        )
        ContactPicker(contacts, selected.toSet(), emptySet(), onToggle = { id -> if (id in selected) selected.remove(id) else selected.add(id) }, Modifier.weight(1f))
    }
}

@Composable
fun GroupInfoScreen(
    session: SessionManager,
    conversationId: String,
    onBack: () -> Unit,
    onLeft: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = remember { session.tokenStore.getUserId() }
    val conv by session.messaging.conversation(conversationId).collectAsState(initial = null)
    var members by remember { mutableStateOf<List<MemberDto>>(emptyList()) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf<MemberDto?>(null) }
    var inviteUrl by remember { mutableStateOf<String?>(null) }
    var inviteBusy by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    val contacts = rememberViroContacts(session)

    suspend fun reload() {
        members = session.messaging.members(conversationId)
        names = members.associate { m ->
            m.userId to (if (m.userId == me) "You" else session.contactsRepository.nameForUserId(m.userId) ?: m.displayName ?: "Viro user")
        }
    }
    LaunchedEffect(conversationId) { reload() }
    val iAmAdmin = members.firstOrNull { it.userId == me }?.role == "ADMIN"
    // The link is an admin thing; asking for it is what creates it.
    LaunchedEffect(iAmAdmin) {
        if (iAmAdmin) session.messaging.groupInvite(conversationId).onSuccess { inviteUrl = it.url }
    }

    val shareLink: () -> Unit = {
        inviteUrl?.let { url ->
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, "Join " + (conv?.title ?: "our group") + " on Viro: " + url)
            }
            runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share group link")) }
        }
    }
    val copyLink: () -> Unit = {
        inviteUrl?.let { url ->
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Viro group link", url))
            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
        }
    }
    val resetLink: () -> Unit = resetLink@{
        if (inviteBusy) return@resetLink
        inviteBusy = true
        scope.launch {
            session.messaging.resetGroupInvite(conversationId)
                .onSuccess {
                    inviteUrl = it.url
                    Toast.makeText(context, "New link made. The old one no longer works.", Toast.LENGTH_LONG).show()
                }
                .onFailure { Toast.makeText(context, "Couldn't make a new link. Try again.", Toast.LENGTH_SHORT).show() }
            inviteBusy = false
        }
    }

    BackHandler { onBack() }
    ViroSubScreen(title = "Group info", onBack = onBack) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            GroupAvatar(conv?.title ?: "Group", 88.dp)
            Spacer(Modifier.height(12.dp))
            Text(conv?.title ?: "Group", color = ViroColors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            conv?.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = ViroColors.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Text("${members.size} ${if (members.size == 1) "member" else "members"}", color = ViroColors.textSecondary, fontSize = 13.sp)
            if (iAmAdmin) TextButton(onClick = { renaming = true }) { Text("Edit name and description", color = ViroColors.accent) }
        }

        if (iAmAdmin) {
            ViroSection(footer = "Anyone with the link can join. Reset it to stop the old one working.") {
                ViroListRow("Add people", icon = Icons.Default.PersonAdd, onClick = { adding = true })
                ViroListRow(
                    "Invite with a link",
                    icon = Icons.Default.Link,
                    subtitle = inviteUrl ?: if (inviteBusy) "Making a link…" else "Link turned off",
                    subtitleMaxLines = 1,
                    onClick = if (inviteUrl != null) shareLink else null,
                )
                Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp, bottom = 4.dp)) {
                    TextButton(onClick = shareLink, enabled = inviteUrl != null) { Text("Share") }
                    TextButton(onClick = copyLink, enabled = inviteUrl != null) { Text("Copy") }
                    TextButton(onClick = resetLink, enabled = !inviteBusy) { Text(if (inviteUrl == null) "Make link" else "Reset") }
                    if (inviteUrl != null) {
                        TextButton(onClick = { confirmRevoke = true }, enabled = !inviteBusy) {
                            Text("Turn off", color = ViroColors.consumerError)
                        }
                    }
                }
            }
        }

        ViroSection(title = "${members.size} ${if (members.size == 1) "member" else "members"}") {
            if (members.isEmpty()) ViroLoadingState()
            members.forEach { m ->
                ViroListRow(
                    title = names[m.userId] ?: "Viro user",
                    value = if (m.role == "ADMIN") "Admin" else null,
                    onClick = if (iAmAdmin && m.userId != me) ({ memberMenu = m }) else null,
                    showChevron = false,
                    leading = { ViroAvatar(displayName = names[m.userId] ?: "?", size = ViroAvatarSize.Small) },
                )
            }
        }

        ViroSection {
            ViroListRow(
                "Leave group",
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                destructive = true,
                showChevron = false,
                onClick = { confirmLeave = true },
            )
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("Turn off the group link?") },
            text = { Text("Nobody can join with the current link any more. You can make a new one later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevoke = false
                    scope.launch {
                        session.messaging.revokeGroupInvite(conversationId)
                            .onSuccess { inviteUrl = null }
                            .onFailure { Toast.makeText(context, "Couldn't do that. Try again.", Toast.LENGTH_SHORT).show() }
                    }
                }) { Text("Turn off", color = ViroColors.consumerError) }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text("Cancel") } },
        )
    }

    memberMenu?.let { m ->
        AlertDialog(
            onDismissRequest = { memberMenu = null },
            title = { Text(names[m.userId] ?: "Member") },
            text = {
                Column {
                    Text(if (m.role == "ADMIN") "Remove as admin" else "Make admin", modifier = Modifier.fillMaxWidth().clickable {
                        memberMenu = null
                        scope.launch {
                            session.messaging.setRole(conversationId, m.userId, m.role != "ADMIN").onFailure {
                                Toast.makeText(context, "Couldn't change their role. Try again.", Toast.LENGTH_SHORT).show()
                            }
                            reload()
                        }
                    }.padding(vertical = 12.dp))
                    Text("Remove from group", color = ViroColors.consumerError, modifier = Modifier.fillMaxWidth().clickable {
                        memberMenu = null
                        scope.launch {
                            session.messaging.removeMember(conversationId, m.userId)
                            reload()
                        }
                    }.padding(vertical = 12.dp))
                }
            },
            confirmButton = { TextButton(onClick = { memberMenu = null }) { Text("Close") } },
        )
    }
    if (adding) {
        val selected = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add people") },
            text = {
                ContactPicker(contacts, selected.toSet(), members.map { it.userId }.toSet(), onToggle = { id ->
                    if (id in selected) selected.remove(id) else selected.add(id)
                }, Modifier.height(420.dp))
            },
            confirmButton = {
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    adding = false
                    scope.launch {
                        session.messaging.addMembers(conversationId, selected.toList())
                        reload()
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    if (renaming) {
        var t by remember { mutableStateOf(conv?.title.orEmpty()) }
        var d by remember { mutableStateOf(conv?.description.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Group name") },
            text = {
                Column {
                    OutlinedTextField(value = t, onValueChange = { t = it.take(120) }, label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = d, onValueChange = { d = it.take(300) }, label = { Text("Description") })
                }
            },
            confirmButton = {
                TextButton(enabled = t.isNotBlank(), onClick = {
                    renaming = false
                    scope.launch { session.messaging.updateGroup(conversationId, t, d) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave group?") },
            text = { Text("You won't receive messages from this group any more.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch {
                        session.messaging.leaveGroup(conversationId).onSuccess { onLeft() }
                    }
                }) { Text("Leave", color = ViroColors.consumerError) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}
