package com.viroreach.app.consumer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.viroreach.app.people.isIncomingRequest
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import com.viroreach.feature.contacts.PhoneNumberFormatter
import kotlinx.coroutines.launch

@Composable
fun ContactsScreen(
    session: SessionManager,
    onCallContact: (ContactListItem) -> Unit,
    onMessageContact: (ContactListItem) -> Unit,
    onContactDetail: (ContactListItem) -> Unit,
    onStartGroupCall: (List<ContactListItem>) -> Unit,
    onAddPeople: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var menuExpanded by remember { mutableStateOf(false) }
    val coordinator = session.contactsCoordinator
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) coordinator.onContactsPermissionGranted() }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    val state = coordinator.uiState

    ViroScreenBackground {
        ViroSafeScreen {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = ViroSpacing.md, vertical = ViroSpacing.md)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Contacts", style = MaterialTheme.typography.headlineMedium, color = ViroColors.textPrimary)
                            Text(
                                "Your people, always within reach.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ViroColors.textSecondary,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Anyone on Viro can be reached by their Viro ID or
                            // email, even with no phone number in the address book.
                            val requests = session.people.connections.collectAsState().value.count { it.isIncomingRequest }
                            IconButton(onClick = onAddPeople) {
                                BadgedBox(badge = { if (requests > 0) Badge { Text("$requests") } }) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = if (requests > 0) "Add people, $requests requests waiting" else "Add people", tint = ViroColors.textPrimary)
                                }
                            }
                            // The only path to a group call used to be inside this
                            // overflow menu, several taps deep with no visible hint
                            // it existed — a single always-visible button makes the
                            // whole flow discoverable at a glance: tap to start
                            // selecting, tap again once you've picked people to call.
                            IconButton(
                                onClick = {
                                    if (selectionMode && selectedIds.isNotEmpty()) {
                                        val selected = state.contacts.filter { it.id in selectedIds }
                                        onStartGroupCall(selected)
                                        selectionMode = false
                                        selectedIds = emptySet()
                                    } else {
                                        selectionMode = true
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Default.Group,
                                    contentDescription = if (selectionMode && selectedIds.isNotEmpty()) {
                                        "Start group call with ${selectedIds.size} selected"
                                    } else {
                                        "New group call"
                                    },
                                    tint = if (selectionMode && selectedIds.isNotEmpty()) {
                                        ViroColors.ElectricBlue
                                    } else {
                                        ViroColors.textPrimary
                                    },
                                )
                            }
                            Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Contact options", tint = ViroColors.textPrimary)
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (selectionMode) "Done selecting" else "Select contacts") },
                                    onClick = {
                                        menuExpanded = false
                                        selectionMode = !selectionMode
                                        if (!selectionMode) selectedIds = emptySet()
                                    },
                                )
                                if (selectionMode && selectedIds.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Start group call") },
                                        onClick = {
                                            menuExpanded = false
                                            val selected = state.contacts.filter { it.id in selectedIds }
                                            onStartGroupCall(selected)
                                            selectionMode = false
                                            selectedIds = emptySet()
                                        },
                                    )
                                }
                            }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    ViroSearchBar(query, { query = it }, "Search contacts")
                    Spacer(Modifier.height(12.dp))
                    ViroFilterChipRow(listOf("All", "Viro", "Other"), filter, { filter = it })
                    if (state.refreshFailed) {
                        Text(
                            "Couldn't refresh",
                            color = ViroColors.consumerWarning,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                when {
                    state.loadState == ContactsLoadState.Loading && state.contacts.isEmpty() -> {
                        Column { repeat(8) { ViroSkeletonContact() } }
                    }
                    state.loadState == ContactsLoadState.Error && state.contacts.isEmpty() -> {
                        ViroEmptyState(
                            "Couldn't load contacts",
                            state.errorMessage ?: "Allow contact access to reach people on Viro.",
                        )
                    }
                    else -> {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                        val filtered = state.contacts.filter { contact ->
                            val matchesQuery = query.isBlank() ||
                                contact.displayName.contains(query, true) ||
                                (contact.phoneE164?.contains(query) == true)
                            val matchesFilter = when (filter) {
                                "Viro" -> contact.isReachable
                                "Other" -> !contact.isReachable
                                else -> true
                            }
                            matchesQuery && matchesFilter
                        }
                        val grouped = filtered.groupBy {
                            it.displayName.firstOrNull { c -> c.isLetter() }?.uppercase() ?: "#"
                        }
                        if (filtered.isEmpty() && state.contacts.isEmpty()) {
                            ViroEmptyState(
                                "No contacts yet",
                                "Allow contact access or add people to reach them on Viro.",
                            )
                        } else if (filtered.isEmpty()) {
                            ViroEmptyState("No matches", "Try a different search or filter.")
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                grouped.toSortedMap().forEach { (letter, contacts) ->
                                    item {
                                        Text(
                                            letter,
                                            modifier = Modifier.padding(horizontal = ViroSpacing.md, vertical = 8.dp),
                                            color = ViroColors.textMuted,
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                    items(contacts, key = { it.id }) { contact ->
                                        ViroContactRow(
                                            name = contact.effectiveDisplayName,
                                            formattedPhone = contact.phoneE164?.let {
                                                PhoneNumberFormatter.formatE164International(it)
                                            },
                                            showViroBadge = contact.isReachable,
                                            imageUrl = contact.resolveAvatarUrl(),
                                            onRowClick = { onContactDetail(contact) },
                                            onCall = { onCallContact(contact) },
                                            onMessage = { onMessageContact(contact) },
                                            onMore = { onContactDetail(contact) },
                                            selectionMode = selectionMode,
                                            selected = contact.id in selectedIds,
                                            onToggleSelect = {
                                                selectedIds = if (contact.id in selectedIds) {
                                                    selectedIds - contact.id
                                                } else {
                                                    selectedIds + contact.id
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        }
                    }
                }
                if (selectionMode && selectedIds.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(ViroSpacing.md),
                        horizontalArrangement = Arrangement.spacedBy(ViroSpacing.sm),
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    session.contactsRepository.setFavorites(selectedIds, true)
                                    selectionMode = false
                                    selectedIds = emptySet()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Favorite")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    session.contactsRepository.hideContacts(selectedIds)
                                    coordinator.refresh(silent = true)
                                    selectionMode = false
                                    selectedIds = emptySet()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ViroColors.consumerError),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}
