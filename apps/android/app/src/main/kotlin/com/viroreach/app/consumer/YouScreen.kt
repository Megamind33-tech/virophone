package com.viroreach.app.consumer

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.viroreach.app.people.visibilityLabel
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import com.viroreach.feature.contacts.PhoneNumberFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
fun YouScreen(
    session: SessionManager,
    showDeveloperEntry: Boolean,
    onDeveloper: () -> Unit,
    onAppearance: () -> Unit,
    onEditProfile: () -> Unit,
    onAddPhone: () -> Unit,
    onBlockedContacts: () -> Unit,
    onConnections: () -> Unit,
    onAddPeople: () -> Unit,
    onDevices: () -> Unit,
    onMediaStorage: () -> Unit,
    onSubscription: () -> Unit,
    onHelp: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = remember(session, showDeveloperEntry) {
        YouViewModel(session, showDeveloperEntry)
    }
    // My own profile as the server knows it: About line and privacy settings.
    val me by session.people.me.collectAsState()
    LaunchedEffect(Unit) { session.people.refreshMe() }
    val profile by session.profileRepository.profile.collectAsState(
        initial = com.viroreach.app.personalization.UserProfile(),
    )
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var callingPrivacyExpanded by remember { mutableStateOf(false) }
    var editingAbout by remember { mutableStateOf<String?>(null) }
    var visibilityFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.refresh()
        session.profileRepository.refreshFromServer()
    }
    val state = viewModel.uiState
    val displayName = profile.displayName.ifBlank { "You" }
    val callingLabel = when (profile.allowCallsFromViroId) {
        "EXACT_ID_ALLOWED" -> "Anyone with my Viro ID"
        else -> "Connections only"
    }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(ViroSpacing.md),
                verticalArrangement = Arrangement.spacedBy(ViroSpacing.md),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ViroAvatar(
                        imageUrl = profile.effectivePhotoUrl,
                        size = ViroAvatarSize.Hero,
                        kind = ViroAvatarKind.USER_PROFILE_IMAGE,
                    )
                    Spacer(Modifier.height(ViroSpacing.sm))
                    Text(displayName, style = MaterialTheme.typography.headlineMedium, color = ViroColors.textPrimary)
                    // An email account has no number, so show the address it
                    // actually signed in with rather than a dash — and never
                    // an email formatted as though it were a phone number.
                    val identityLine = state.phoneE164
                        ?.let { PhoneNumberFormatter.formatE164International(it) }
                        ?: state.email
                    identityLine?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = ViroColors.textSecondary,
                        )
                    }
                    Text(
                        "Edit profile",
                        color = ViroColors.accent,
                        modifier = Modifier
                            .clickable(onClick = onEditProfile)
                            .padding(vertical = ViroSpacing.sm),
                    )
                }
                SettingsSection(title = "Account") {
                    SettingsNavRow("Profile", onEditProfile)
                    // An account with no number is invisible to contact
                    // discovery, so the row is an action rather than a dash:
                    // someone who skipped the step at sign-up had no way back
                    // to it short of signing out.
                    if (state.phoneE164.isNullOrBlank()) {
                        SettingsNavRow("Add phone number", onAddPhone)
                    } else {
                        SettingsRow(
                            label = "Phone",
                            value = PhoneNumberFormatter.formatE164International(state.phoneE164),
                        )
                    }
                    state.email?.takeIf { it.isNotBlank() }?.let {
                        SettingsRow(label = "Email", value = it)
                    }
                    // How people reach this account without its phone number.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Viro ID", style = MaterialTheme.typography.bodyLarge, color = ViroColors.textPrimary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                profile.viroId ?: "Not set",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ViroColors.textSecondary,
                            )
                            profile.viroId?.let { id ->
                                Text(
                                    "Share",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ViroColors.accent,
                                    modifier = Modifier
                                        .clickable {
                                            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    android.content.Intent.EXTRA_TEXT,
                                                    "Reach me on Viro: " + id + System.lineSeparator() + com.viroreach.app.people.inviteLink(id),
                                                )
                                            }
                                            runCatching {
                                                context.startActivity(android.content.Intent.createChooser(share, "Share your Viro ID"))
                                            }
                                        }
                                        .padding(start = ViroSpacing.md),
                                )
                            }
                        }
                    }
                    val about = me?.about
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { editingAbout = about.orEmpty() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("About", style = MaterialTheme.typography.bodyLarge, color = ViroColors.textPrimary)
                        Text(
                            about?.takeIf { it.isNotBlank() } ?: "Add a line",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (about.isNullOrBlank()) ViroColors.accent else ViroColors.textSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = ViroSpacing.md).weight(1f, fill = false),
                        )
                    }
                    SettingsNavRow("Subscription", onSubscription)
                }
                SettingsSection(title = "Preferences") {
                    SettingsNavRow("Appearance", onAppearance)
                    SettingsNavRow("Media and storage", onMediaStorage)
                    SettingsNavRow("Calling privacy") { callingPrivacyExpanded = true }
                    SettingsRow(label = "Calling", value = callingLabel)
                    SettingsRow(label = "Notifications", value = "Coming soon")
                }
                SettingsSection(title = "Privacy & Security") {
                    // Who sees what about me.
                    SettingsRowClickable("Last seen", visibilityLabel(me?.lastSeenVisibility)) { visibilityFor = "lastSeen" }
                    SettingsRowClickable("Profile photo", visibilityLabel(me?.photoVisibility)) { visibilityFor = "photo" }
                    SettingsRowClickable("About", visibilityLabel(me?.aboutVisibility)) { visibilityFor = "about" }
                    SettingsNavRow("Add people", onAddPeople)
                    SettingsNavRow("Connections", onConnections)
                    // Exact-match only, and only for a verified address.
                    val findableByEmail = me?.discoverableByEmail ?: true
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Find me by email", style = MaterialTheme.typography.bodyLarge, color = ViroColors.textPrimary)
                            Text(
                                "People who type your exact email address can find you.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ViroColors.textSecondary,
                            )
                        }
                        Switch(
                            checked = findableByEmail,
                            onCheckedChange = { on ->
                                scope.launch {
                                    session.people.update(
                                        com.viroreach.core.network.UpdateMeBody(discoverableByEmail = on),
                                    )
                                }
                            },
                        )
                    }
                    SettingsNavRow("Blocked contacts", onBlockedContacts)
                    SettingsNavRow("Devices", onDevices)
                }
                SettingsSection(title = "Support") {
                    SettingsNavRow("Help", onHelp)
                    SettingsRow(label = "About Viro", value = state.versionName)
                }
                SettingsSection(title = "Account data") {
                    SettingsNavRow("Download my data") {
                        if (exporting) return@SettingsNavRow
                        scope.launch {
                            exporting = true
                            exportStatus = null
                            runCatching {
                                val export = session.api.exportAccount()
                                val json = JSONObject().apply {
                                    put("userId", export.userId)
                                    put("exportedAt", export.exportedAt)
                                    put("profile", JSONObject().apply {
                                        put("displayName", export.profile?.displayName)
                                        put("phoneE164", export.profile?.phoneE164)
                                        put("viroId", export.profile?.viroId)
                                    })
                                    put("calls", JSONArray(export.calls.map {
                                        JSONObject().apply {
                                            put("id", it.id)
                                            put("status", it.status)
                                            put("startedAt", it.startedAt)
                                        }
                                    }))
                                    put("blocks", JSONArray(export.blocks.map {
                                        JSONObject().apply { put("blockedUserId", it.blockedUserId) }
                                    }))
                                    put("devices", JSONArray(export.devices.map {
                                        JSONObject().apply {
                                            put("id", it.id)
                                            put("platform", it.platform)
                                        }
                                    }))
                                    put("messages", JSONArray(export.messages.map {
                                        JSONObject().apply {
                                            put("id", it.id)
                                            put("body", it.body)
                                        }
                                    }))
                                }.toString(2)
                                val file = withContext(Dispatchers.IO) {
                                    File(context.cacheDir, "viro-export-${System.currentTimeMillis()}.json").also {
                                        it.writeText(json)
                                    }
                                }
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        "Share account export",
                                    ),
                                )
                                exportStatus = "Export ready"
                            }.onFailure {
                                exportStatus = "Couldn't export account data"
                            }
                            exporting = false
                        }
                    }
                    if (exporting) {
                        Text("Preparing export…", color = ViroColors.textSecondary)
                    }
                    exportStatus?.let {
                        Text(it, color = ViroColors.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    SettingsNavRow("Delete account") { confirmDelete = true }
                    deleteError?.let {
                        Text(it, color = ViroColors.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.showDeveloperEntry) {
                    SettingsSection(title = "Internal") {
                        SettingsNavRow("Developer", onDeveloper)
                    }
                }
                ViroSecondaryButton(
                    text = "Log out",
                    onClick = {
                        viewModel.logout()
                        onLogout()
                    },
                )
            }
        }
    }

    if (callingPrivacyExpanded) {
        AlertDialog(
            onDismissRequest = { callingPrivacyExpanded = false },
            title = { Text("Who can call you?") },
            text = {
                Column {
                    TextButton(onClick = {
                        scope.launch {
                            session.profileRepository.setAllowCallsFromViroId("CONNECTIONS_ONLY")
                            callingPrivacyExpanded = false
                        }
                    }) { Text("Connections only") }
                    TextButton(onClick = {
                        scope.launch {
                            session.profileRepository.setAllowCallsFromViroId("EXACT_ID_ALLOWED")
                            callingPrivacyExpanded = false
                        }
                    }) { Text("Anyone with my Viro ID") }
                }
            },
            confirmButton = {
                TextButton(onClick = { callingPrivacyExpanded = false }) { Text("Close") }
            },
        )
    }

    editingAbout?.let { current ->
        var text by remember(current) { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editingAbout = null },
            title = { Text("About") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (it.length <= 139) text = it },
                        label = { Text("Say something about yourself") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${text.length} of 139",
                        style = MaterialTheme.typography.bodySmall,
                        color = ViroColors.textMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingAbout = null
                    scope.launch {
                        session.people.update(com.viroreach.core.network.UpdateMeBody(about = text.trim()))
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingAbout = null }) { Text("Cancel") } },
        )
    }

    visibilityFor?.let { which ->
        val title = when (which) {
            "lastSeen" -> "Who can see when you were last seen"
            "photo" -> "Who can see your profile photo"
            else -> "Who can read your About line"
        }
        val current = when (which) {
            "lastSeen" -> me?.lastSeenVisibility
            "photo" -> me?.photoVisibility
            else -> me?.aboutVisibility
        }
        AlertDialog(
            onDismissRequest = { visibilityFor = null },
            title = { Text(title) },
            text = {
                Column {
                    com.viroreach.app.people.VISIBILITY_LABELS.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                visibilityFor = null
                                scope.launch {
                                    session.people.update(
                                        when (which) {
                                            "lastSeen" -> com.viroreach.core.network.UpdateMeBody(lastSeenVisibility = value)
                                            "photo" -> com.viroreach.core.network.UpdateMeBody(photoVisibility = value)
                                            else -> com.viroreach.core.network.UpdateMeBody(aboutVisibility = value)
                                        },
                                    )
                                }
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = (current ?: "EVERYONE").uppercase() == value, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = ViroColors.textPrimary)
                        }
                    }
                    if (which == "lastSeen") {
                        Text(
                            "If you don't share when you were last seen, you won't see it for other people either.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ViroColors.textMuted,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { visibilityFor = null }) { Text("Close") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("Delete account?") },
            text = {
                Text(
                    "This permanently closes your Viro identity, signs out every device, " +
                        "and removes you from discovery. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        scope.launch {
                            deleting = true
                            runCatching { session.api.deleteAccount() }
                                .onSuccess {
                                    viewModel.logout()
                                    confirmDelete = false
                                    onLogout()
                                }
                                .onFailure {
                                    deleteError = "Couldn't delete the account. Try again."
                                    deleting = false
                                }
                        }
                    },
                ) { Text(if (deleting) "Deleting…" else "Delete") }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { confirmDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ViroSpacing.xs)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = ViroColors.textMuted)
        Card(colors = CardDefaults.cardColors(containerColor = ViroColors.surface)) {
            Column(
                Modifier.padding(ViroSpacing.md),
                verticalArrangement = Arrangement.spacedBy(ViroSpacing.sm),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsNavRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = ViroColors.accent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = ViroSpacing.sm),
    )
}

@Composable
private fun SettingsRowClickable(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ViroColors.textPrimary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = ViroColors.accent)
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ViroColors.textPrimary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = ViroColors.textSecondary)
    }
}
