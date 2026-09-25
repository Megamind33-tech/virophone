package com.viroreach.app.consumer

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
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
    var showBackup by remember { mutableStateOf(false) }
    var callingPrivacyExpanded by remember { mutableStateOf(false) }
    var editingAbout by remember { mutableStateOf<String?>(null) }
    var editingAboutYou by remember { mutableStateOf(false) }
    if (editingAboutYou) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { editingAboutYou = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            com.viroreach.app.people.AboutYouEditor(session = session, onDone = { editingAboutYou = false })
        }
    }
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

    val exportData: () -> Unit = exportData@{
        if (exporting) return@exportData
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
    val shareViroId: (String) -> Unit = { id ->
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
    val openNotificationSettings: () -> Unit = {
        // Android already owns this. Messages, calls and live location each
        // have their own channel, so the system's own page is the real control
        // surface: every switch on it works, and it keeps working when a
        // channel is added. A second set inside Viro would have to be obeyed
        // at every point a notification is posted, and would quietly disagree
        // with the ones underneath it.
        val perApp = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        // Not every phone has the per-app notification page; all of them
        // have app details, and its notification entry is one tap further.
        val details = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(perApp) }
            .recoverCatching { context.startActivity(details) }
    }
    // An email account has no number, so show the address it actually signed
    // in with rather than a dash — and never an email formatted as a number.
    val identityLine = state.phoneE164
        ?.let { PhoneNumberFormatter.formatE164International(it) }
        ?: state.email

    ViroScreenBackground {
        ViroSafeScreen {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = ViroSpacing.md, end = ViroSpacing.md, bottom = ViroSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // The same tab header as Contacts and Chats.
                Column(Modifier.padding(top = ViroSpacing.md)) {
                    Text("You", style = MaterialTheme.typography.headlineMedium, color = ViroColors.textPrimary)
                    Text(
                        "Your profile, privacy and settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ViroColors.textSecondary,
                    )
                }

                // Who you are, as a card you can tap into.
                Surface(
                    onClick = onEditProfile,
                    shape = ViroSectionShape,
                    color = viroGroupedSurface(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ViroAvatar(
                            imageUrl = profile.effectivePhotoUrl,
                            size = ViroAvatarSize.Large,
                            kind = ViroAvatarKind.USER_PROFILE_IMAGE,
                            displayName = displayName,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                displayName,
                                style = MaterialTheme.typography.titleLarge,
                                color = ViroColors.textPrimary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            identityLine?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = ViroColors.textSecondary, maxLines = 1)
                            }
                            me?.about?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ViroColors.textMuted,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Edit profile", style = MaterialTheme.typography.labelLarge, color = ViroColors.accent)
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = ViroColors.textMuted,
                        )
                    }
                }

                ViroSection(title = "Account") {
                    // An account with no number is invisible to contact
                    // discovery, so the row is an action rather than a dash:
                    // someone who skipped the step at sign-up had no way back
                    // to it short of signing out.
                    if (state.phoneE164.isNullOrBlank()) {
                        ViroListRow("Add phone number", icon = Icons.Outlined.Phone, subtitle = "So people with your number can find you", onClick = onAddPhone)
                    } else {
                        ViroListRow("Phone", icon = Icons.Outlined.Phone, value = PhoneNumberFormatter.formatE164International(state.phoneE164))
                    }
                    state.email?.takeIf { it.isNotBlank() }?.let {
                        ViroListRow("Email", icon = Icons.Outlined.Email, value = it)
                    }
                    // How people reach this account without its phone number.
                    ViroListRow(
                        "Viro ID",
                        icon = Icons.Outlined.AlternateEmail,
                        value = profile.viroId ?: "Not set",
                        onClick = profile.viroId?.let { id -> { shareViroId(id) } },
                        trailing = profile.viroId?.let {
                            { Icon(Icons.Outlined.Share, contentDescription = "Share your Viro ID", tint = ViroColors.accent, modifier = Modifier.size(20.dp)) }
                        },
                    )
                    val about = me?.about
                    ViroListRow(
                        "About",
                        icon = Icons.Outlined.Info,
                        value = about?.takeIf { it.isNotBlank() } ?: "Add a line",
                        onClick = { editingAbout = about.orEmpty() },
                    )
                    ViroListRow("About you", icon = Icons.Outlined.Interests, subtitle = "Birthday and the things you like", onClick = { editingAboutYou = true })
                    ViroListRow("Subscription", icon = Icons.Outlined.WorkspacePremium, onClick = onSubscription)
                }

                ViroSection(title = "Privacy") {
                    ViroListRow("Last seen", icon = Icons.Outlined.Schedule, value = visibilityLabel(me?.lastSeenVisibility), onClick = { visibilityFor = "lastSeen" })
                    ViroListRow("Profile photo", icon = Icons.Outlined.AccountCircle, value = visibilityLabel(me?.photoVisibility), onClick = { visibilityFor = "photo" })
                    ViroListRow("About line", icon = Icons.Outlined.ChatBubbleOutline, value = visibilityLabel(me?.aboutVisibility), onClick = { visibilityFor = "about" })
                    ViroListRow("Who can call me", icon = Icons.Outlined.Call, value = callingLabel, onClick = { callingPrivacyExpanded = true })
                    // Exact-match only, and only for a verified address.
                    ViroSwitchRow(
                        title = "Find me by email",
                        subtitle = "People who type your exact email address can find you.",
                        icon = Icons.Outlined.Search,
                        checked = me?.discoverableByEmail ?: true,
                        onCheckedChange = { on ->
                            scope.launch {
                                session.people.update(com.viroreach.core.network.UpdateMeBody(discoverableByEmail = on))
                            }
                        },
                    )
                    ViroListRow("Blocked contacts", icon = Icons.Outlined.Block, onClick = onBlockedContacts)
                }

                ViroSection(title = "People") {
                    ViroListRow("Add people", icon = Icons.Outlined.PersonAdd, onClick = onAddPeople)
                    ViroListRow("Connections", icon = Icons.Outlined.People, onClick = onConnections)
                }

                ViroSection(title = "Preferences") {
                    ViroListRow("Appearance", icon = Icons.Outlined.Palette, onClick = onAppearance)
                    ViroListRow("Notifications", icon = Icons.Outlined.Notifications, onClick = openNotificationSettings)
                    ViroListRow("Media and storage", icon = Icons.Outlined.Folder, onClick = onMediaStorage)
                }

                ViroSection(
                    title = "Devices and data",
                    footer = when {
                        exporting -> "Preparing your export…"
                        exportStatus != null -> exportStatus
                        deleteError != null -> deleteError
                        else -> null
                    },
                ) {
                    ViroListRow("Linked devices", icon = Icons.Outlined.Devices, onClick = onDevices)
                    // Encrypted chats live only on the phone that opened them,
                    // so this is what stands between a reinstall and losing them.
                    ViroListRow("Chat backup", icon = Icons.Outlined.Backup, onClick = { showBackup = true })
                    ViroListRow("Download my data", icon = Icons.Outlined.Download, enabled = !exporting, onClick = exportData)
                    ViroListRow("Delete account", icon = Icons.Outlined.DeleteForever, destructive = true, onClick = { confirmDelete = true })
                }

                ViroSection(title = "Support") {
                    ViroListRow("Help", icon = Icons.AutoMirrored.Outlined.HelpOutline, onClick = onHelp)
                    ViroListRow("About Viro", icon = Icons.Outlined.Info, value = state.versionName)
                }

                if (state.showDeveloperEntry) {
                    ViroSection(title = "Internal") {
                        ViroListRow("Developer", icon = Icons.Outlined.Code, onClick = onDeveloper)
                    }
                }

                ViroSection {
                    ViroListRow(
                        "Log out",
                        icon = Icons.AutoMirrored.Outlined.Logout,
                        destructive = true,
                        showChevron = false,
                        onClick = {
                            viewModel.logout()
                            onLogout()
                        },
                    )
                }
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

    if (showBackup) {
        ChatBackupDialog(session) { showBackup = false }
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
                                    // A deleted account's messages and keys
                                    // have no one left to return to them.
                                    runCatching { session.messaging.clearLocal() }
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
