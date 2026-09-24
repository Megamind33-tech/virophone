package com.viroreach.app.consumer.messages

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search contacts on Viro") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        val shown = contacts.filter { it.userId !in exclude && (q.isBlank() || it.effectiveDisplayName.contains(q, true)) }
        if (shown.isEmpty()) {
            Text("None of your contacts matching that are on Viro yet.", color = ViroColors.textSecondary, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.userId!! }) { c ->
                val id = c.userId!!
                Row(
                    Modifier.fillMaxWidth().clickable { onToggle(id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViroAvatar(displayName = c.effectiveDisplayName, imageUrl = c.resolveAvatarUrl(), size = ViroAvatarSize.Small)
                    Spacer(Modifier.width(12.dp))
                    Text(c.effectiveDisplayName, color = ViroColors.textPrimary, modifier = Modifier.weight(1f))
                    Checkbox(checked = id in selected, onCheckedChange = { onToggle(id) })
                }
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
    Column(Modifier.fillMaxSize().background(ViroColors.background).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = ViroColors.textPrimary) }
            Text("New group", color = ViroColors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(
                enabled = title.isNotBlank() && selected.isNotEmpty() && !creating,
                onClick = {
                    creating = true
                    scope.launch {
                        session.messaging.createGroup(title, selected.toList())
                            .onSuccess { onCreated(it, title.trim()) }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                        creating = false
                    }
                },
            ) { Text(if (creating) "Creating…" else "Create", color = ViroColors.accent) }
        }
        OutlinedTextField(
            value = title, onValueChange = { title = it.take(120) }, singleLine = true,
            label = { Text("Group name") }, modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        Text("${selected.size} selected", color = ViroColors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
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

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(ViroColors.background).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = ViroColors.textPrimary) }
            Text("Group info", color = ViroColors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    GroupAvatar(conv?.title ?: "Group", 88.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(conv?.title ?: "Group", color = ViroColors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    conv?.description?.let { Text(it, color = ViroColors.textSecondary) }
                    Text("${members.size} ${if (members.size == 1) "member" else "members"}", color = ViroColors.textSecondary, fontSize = 13.sp)
                    if (iAmAdmin) TextButton(onClick = { renaming = true }) { Text("Edit name and description") }
                }
            }
            if (iAmAdmin) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.PersonAdd, null, tint = ViroColors.accent)
                        Spacer(Modifier.width(14.dp))
                        Text("Add people", color = ViroColors.accent)
                    }
                }
            }
            if (iAmAdmin) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Link, null, tint = ViroColors.accent)
                            Spacer(Modifier.width(14.dp))
                            Text("Invite with a link", color = ViroColors.textPrimary, modifier = Modifier.weight(1f))
                        }
                        Text(
                            inviteUrl ?: "Making a link…",
                            color = ViroColors.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 4.dp, start = 38.dp),
                        )
                        Row(Modifier.padding(start = 30.dp)) {
                            TextButton(
                                onClick = {
                                    inviteUrl?.let { url ->
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(
                                                android.content.Intent.EXTRA_TEXT,
                                                "Join " + (conv?.title ?: "our group") + " on Viro: " + url,
                                            )
                                        }
                                        runCatching {
                                            context.startActivity(android.content.Intent.createChooser(send, "Share group link"))
                                        }
                                    }
                                },
                                enabled = inviteUrl != null,
                            ) { Text("Share") }
                            TextButton(
                                onClick = {
                                    inviteUrl?.let { url ->
                                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Viro group link", url))
                                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = inviteUrl != null,
                            ) { Text("Copy") }
                            TextButton(
                                onClick = {
                                    if (inviteBusy) return@TextButton
                                    inviteBusy = true
                                    scope.launch {
                                        session.messaging.resetGroupInvite(conversationId)
                                            .onSuccess {
                                                inviteUrl = it.url
                                                Toast.makeText(context, "New link made. The old one no longer works.", Toast.LENGTH_LONG).show()
                                            }
                                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                                        inviteBusy = false
                                    }
                                },
                                enabled = !inviteBusy,
                            ) { Text("Reset") }
                            if (inviteUrl != null) {
                                TextButton(onClick = { confirmRevoke = true }, enabled = !inviteBusy) {
                                    Text("Turn off", color = ViroColors.consumerError)
                                }
                            }
                        }
                    }
                }
            }
            items(members, key = { it.userId }) { m ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = iAmAdmin && m.userId != me) { memberMenu = m }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViroAvatar(displayName = names[m.userId] ?: "?", size = ViroAvatarSize.Small)
                    Spacer(Modifier.width(12.dp))
                    Text(names[m.userId] ?: "Viro user", color = ViroColors.textPrimary, modifier = Modifier.weight(1f))
                    if (m.role == "ADMIN") Text("Admin", color = ViroColors.accent, fontSize = 12.sp)
                }
            }
            item {
                TextButton(onClick = { confirmLeave = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Icon(Icons.Default.ExitToApp, null, tint = ViroColors.consumerError)
                    Spacer(Modifier.width(8.dp))
                    Text("Leave group", color = ViroColors.consumerError)
                }
            }
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
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
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
                                Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
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
