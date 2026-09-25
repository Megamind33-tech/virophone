package com.viroreach.app.consumer

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viroreach.app.personalization.ProfilePhotoCapture
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import com.viroreach.feature.contacts.PhoneNumberFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun ContactDetailScreen(
    contact: ContactListItem,
    session: SessionManager,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onOpenRelated: (ContactListItem) -> Unit,
    onDelete: () -> Unit,
    onOpenRelationship: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember(contact.id) { mutableStateOf(contact) }
    var isEditingName by remember { mutableStateOf(false) }
    var editedName by remember(profile.effectiveDisplayName) { mutableStateOf(profile.effectiveDisplayName) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // What they let me see: About line, and when they were last here.
    var publicProfile by remember(contact.userId) { mutableStateOf<com.viroreach.core.network.PublicProfileDto?>(null) }
    LaunchedEffect(contact.userId) {
        val id = contact.userId ?: return@LaunchedEffect
        publicProfile = runCatching { session.api.publicProfile(id) }.getOrNull()
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showPhotoOptions by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val callHistory by session.callHistoryStore.entries.collectAsState()
    var relatedContacts by remember { mutableStateOf<List<ContactListItem>>(emptyList()) }
    val messages by remember(profile.userId) {
        profile.userId?.let { peer ->
            kotlinx.coroutines.flow.flow { emit(session.messaging.conversationIdFor(peer)) }
                .flatMapLatest { id -> session.messaging.messages(id) }
        } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val history = remember(profile.phoneE164, callHistory, messages) {
        ContactCommunicationHistory.build(profile.phoneE164, session.callHistoryStore, messages)
    }

    LaunchedEffect(contact.id) {
        profile = session.contactsRepository.ensureCached(contact)
        relatedContacts = session.contactsRepository.relatedContacts(profile)
    }

    LaunchedEffect(profile.id) {
        relatedContacts = session.contactsRepository.relatedContacts(profile)
    }

    fun uploadPhoto(uri: Uri) {
        scope.launch {
            // The picked URI is copied into app storage rather than kept: the
            // picker's grant and the camera's cache file both go away, which
            // is why a contact photo used to fail or disappear.
            runCatching {
                // Make sure the row exists first: a device contact opened
                // before the cache caught up has no row, and the patch would
                // fail with "Contact not found".
                val cached = session.contactsRepository.ensureCached(profile)
                val stored = withContext(Dispatchers.IO) {
                    ProfilePhotoCapture.storeContactPhoto(context, cached.id, uri)
                }
                session.contactsRepository.setCustomPhoto(cached.id, stored)
                session.contactsRepository.findById(cached.id)
                    ?: cached.copy(customPhotoUri = stored.toString())
            }.onSuccess {
                profile = it
                statusMessage = "Photo updated"
            }.onFailure {
                statusMessage = "Couldn't set that photo. Try another one."
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { uploadPhoto(it) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let { uploadPhoto(it) }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            val uri = ProfilePhotoCapture.createCameraUri(context)
            cameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            statusMessage = "Camera permission is required to take a photo"
        }
    }

    fun shareContact() {
        val phone = profile.phoneE164?.let { PhoneNumberFormatter.formatE164International(it) }.orEmpty()
        val text = buildString {
            append("Connect with ${profile.effectiveDisplayName} on Viro")
            if (phone.isNotBlank()) append("\n$phone")
            append("\nhttps://reach.viro3.online")
        }
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Share contact",
            ),
        )
    }

    if (showPhotoOptions) {
        AlertDialog(
            onDismissRequest = { showPhotoOptions = false },
            title = { Text("Contact photo") },
            confirmButton = {
                TextButton(onClick = {
                    showPhotoOptions = false
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Gallery") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPhotoOptions = false
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                }) { Text("Camera") }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete contact?") },
            text = { Text("Removes ${profile.effectiveDisplayName} from Viro. Your device contact is not changed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        session.contactsRepository.hideContacts(setOf(profile.id))
                        onDelete()
                    }
                }) { Text("Delete", color = ViroColors.consumerError) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }

    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text("Block contact?") },
            text = { Text("${profile.effectiveDisplayName} won't be able to call or message you on Viro.") },
            confirmButton = {
                TextButton(onClick = {
                    showBlockConfirm = false
                    scope.launch {
                        session.contactsRepository.blockContact(profile.id)
                            .onSuccess {
                                statusMessage = "Contact blocked"
                                onDelete()
                            }
                            .onFailure { statusMessage = "Couldn't block contact" }
                    }
                }) { Text("Block", color = ViroColors.consumerError) }
            },
            dismissButton = { TextButton(onClick = { showBlockConfirm = false }) { Text("Cancel") } },
        )
    }

    ViroSubScreen(
        title = "",
        onBack = onBack,
        actions = {
            IconButton(onClick = { shareContact() }) {
                Icon(Icons.Default.Share, contentDescription = "Share contact", tint = ViroColors.textPrimary)
            }
        },
    ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        ViroAvatar(
                            imageUrl = profile.resolveAvatarUrl(),
                            size = ViroAvatarSize.Hero,
                        )
                        FilledIconButton(
                            onClick = { showPhotoOptions = true },
                            modifier = Modifier.size(38.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ViroColors.accent),
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = "Change photo", tint = ViroColors.onAccent, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(ViroSpacing.md))
                    if (isEditingName) {
                        ViroTextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            label = "Name",
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { isEditingName = false }) { Text("Cancel") }
                            TextButton(onClick = {
                                scope.launch {
                                    session.contactsRepository.updateDisplayName(profile.id, editedName)
                                    profile = session.contactsRepository.findById(profile.id)
                                        ?: profile.copy(customDisplayName = editedName)
                                    isEditingName = false
                                }
                            }) { Text("Save") }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                profile.effectiveDisplayName,
                                style = MaterialTheme.typography.headlineMedium,
                                color = ViroColors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = {
                                editedName = profile.effectiveDisplayName
                                isEditingName = true
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit name", tint = ViroColors.textSecondary)
                            }
                        }
                    }
                    profile.phoneE164?.let {
                        Text(
                            PhoneNumberFormatter.formatE164International(it),
                            style = MaterialTheme.typography.bodyLarge,
                            color = ViroColors.textSecondary,
                        )
                    }
                    if (profile.isReachable) {
                        Text("On Viro", color = ViroColors.success, style = MaterialTheme.typography.labelMedium)
                    }
                    // Only what this person allows: an empty About or a hidden
                    // last seen simply isn't there.
                    publicProfile?.about?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ViroColors.textPrimary,
                            modifier = Modifier.padding(top = ViroSpacing.sm),
                        )
                    }
                    com.viroreach.app.people.lastSeenLabel(
                        com.viroreach.app.messaging.parseIso(publicProfile?.lastSeenAt),
                    )?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = ViroColors.textSecondary)
                    }
                    statusMessage?.let {
                        Text(it, color = ViroColors.textSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                }
                // What they have chosen to let this person know about them.
                // Only answered topics, only as each one allows — and nothing
                // at all across a block, which the server already enforces.
                publicProfile?.let { p ->
                    com.viroreach.app.people.AboutYouSection(
                        name = p.displayName ?: profile.effectiveDisplayName,
                        aboutYou = p.aboutYou,
                        birthday = p.birthday,
                        modifier = Modifier.padding(top = ViroSpacing.lg),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProfileActionChip("Call", Icons.Default.Call, onCall, Modifier.weight(1f))
                    ProfileActionChip("Message", Icons.AutoMirrored.Filled.Chat, onMessage, Modifier.weight(1f))
                    ProfileActionChip("Share", Icons.Default.Share, { shareContact() }, Modifier.weight(1f))
                }
                // Targets, dates, Loops and Moments for this person — private to you.
                ViroSection {
                    ViroListRow(
                        "Relationship, targets and Moments",
                        subtitle = "Only you can see this",
                        icon = Icons.Default.FavoriteBorder,
                        onClick = onOpenRelationship,
                    )
                }
                ViroSection(title = "Recent activity") {
                    if (history.isEmpty()) {
                        ViroListRow("No calls or messages yet", icon = Icons.Default.History, enabled = false)
                    } else {
                        history.take(12).forEach { item -> ContactHistoryRow(item) }
                    }
                }
                if (relatedContacts.isNotEmpty()) {
                    ViroSection(title = "Other numbers") {
                        relatedContacts.forEach { related ->
                            ViroListRow(
                                title = related.phoneE164?.let { PhoneNumberFormatter.formatE164International(it) } ?: related.effectiveDisplayName,
                                subtitle = if (related.isReachable) "On Viro" else null,
                                leading = { ViroAvatar(imageUrl = related.resolveAvatarUrl(), size = ViroAvatarSize.Small) },
                                onClick = { onOpenRelated(related) },
                            )
                        }
                    }
                }
                ViroSection {
                    ViroListRow("Invite to Viro", icon = Icons.Default.PersonAdd, showChevron = false, onClick = {
                        scope.launch {
                            if (profile.userId != null) {
                                session.contactsRepository.inviteContact(profile.id)
                                    .onSuccess { statusMessage = it }
                                    .onFailure { shareContact() }
                            } else {
                                shareContact()
                            }
                        }
                    })
                    // Spam sits above Block deliberately: it is the lighter
                    // action and the one people reach for first, before they
                    // are sure enough to cut someone off entirely.
                    ViroListRow(
                        if (profile.isSpam) "Not spam" else "Mark as spam",
                        icon = Icons.Default.Report,
                        showChevron = false,
                        onClick = {
                            scope.launch {
                                session.contactsRepository.setSpam(profile.id, !profile.isSpam)
                                val nowSpam = !profile.isSpam
                                statusMessage = if (nowSpam) "Marked as spam" else "Removed spam mark"
                                // Local state drives the row label, so flip it
                                // here rather than waiting for a reload.
                                profile = profile.copy(isSpam = nowSpam)
                            }
                        },
                    )
                    ViroListRow("Block ${profile.effectiveDisplayName.substringBefore(' ')}", icon = Icons.Default.Block, destructive = true, showChevron = false, onClick = { showBlockConfirm = true })
                    ViroListRow("Delete contact", icon = Icons.Default.Delete, destructive = true, showChevron = false, onClick = { showDeleteConfirm = true })
                }
    }
}

@Composable
private fun ProfileActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = viroGroupedSurface(),
        modifier = modifier.height(72.dp),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = ViroColors.accent)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = ViroColors.textPrimary)
        }
    }
}

@Composable
private fun ContactHistoryRow(item: ContactHistoryItem) {
    ViroListRow(
        title = item.label,
        subtitle = item.detail,
        subtitleMaxLines = 1,
        icon = if (item.kind == ContactHistoryKind.CALL) Icons.Default.Call else Icons.AutoMirrored.Filled.Chat,
        value = formatHistoryTime(item.timestampMs),
    )
}

private fun formatHistoryTime(ms: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))
