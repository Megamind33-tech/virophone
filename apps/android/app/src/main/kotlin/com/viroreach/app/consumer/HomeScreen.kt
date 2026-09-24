package com.viroreach.app.consumer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viroreach.app.consumer.data.CallLogEntry
import com.viroreach.app.consumer.data.CallLogType
import com.viroreach.app.session.ReachabilityMapper
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import com.viroreach.feature.contacts.PhoneNumberFormatter
import kotlinx.coroutines.launch

private val HomeFabScrollPadding = 100.dp
private const val HomeFavoritesPreviewCount = 4
private const val HomeRecentsPreviewCount = 5

@Composable
fun HomeScreen(
    session: SessionManager,
    onOpenDialer: () -> Unit,
    onCallContact: (ContactListItem) -> Unit,
    onContactDetail: (ContactListItem) -> Unit,
    onViewCallLog: (CallLogEntry) -> Unit,
    onMessageCallLog: (CallLogEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val contactsState = session.contactsCoordinator.uiState
    val recents by session.callHistoryStore.entries.collectAsState()
    val allFavorites = remember(contactsState.contacts) {
        contactsState.contacts.filter { it.isFavorite }
    }
    var favoritesExpanded by remember { mutableStateOf(false) }
    var recentsExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val contactsByPhone = remember(contactsState.contacts) {
        contactsState.contacts.mapNotNull { contact ->
            contact.phoneE164?.let { it to contact }
        }.toMap()
    }
    // Server-synced call history never carries a phone number (see
    // ServerCallHistoryMapper), only peerUserId — without this, every such
    // entry's name/avatar lookup silently missed and fell back to a raw
    // "<userId prefix>" label even when the contact is saved locally.
    val contactsByUserId = remember(contactsState.contacts) {
        contactsState.contacts.mapNotNull { contact ->
            contact.userId?.let { it to contact }
        }.toMap()
    }

    val searchResults = remember(searchQuery, contactsState.contacts) {
        if (searchQuery.isBlank()) emptyList()
        else contactsState.contacts.filter { contact ->
            contact.effectiveDisplayName.contains(searchQuery, true) ||
                (contact.phoneE164?.contains(searchQuery.filter { it.isDigit() || it == '+' }) == true)
        }.take(8)
    }

    val hasInternet by session.networkMonitor.hasInternet.collectAsState()
    val internetValidated by session.networkMonitor.internetValidated.collectAsState()
    val wss by session.callManager.wssConnectionState.collectAsState()
    val reachability = ReachabilityMapper.map(
        hasInternet = hasInternet,
        wssState = wss,
        isAuthenticated = session.isAuthenticated,
        internetValidated = internetValidated,
    )

    ViroScreenBackground {
        ViroSafeScreen {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ViroSpacing.md),
            ) {
                Spacer(Modifier.height(ViroSpacing.xs))
                // No in-app branding here; the only thing in the top corner is
                // the connection dot.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViroStatusIndicator(state = ReachabilityMapper.toVisual(reachability))
                }
                Spacer(Modifier.height(ViroSpacing.sm))
                ViroSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search or enter number",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (searchQuery.isNotBlank()) {
                    Spacer(Modifier.height(ViroSpacing.sm))
                    if (searchResults.isEmpty()) {
                        Text(
                            "No contacts match \"$searchQuery\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(ViroSpacing.sm))
                        TextButton(onClick = onOpenDialer) {
                            Text("Open dialer to call $searchQuery")
                        }
                    } else {
                        searchResults.forEach { contact ->
                            ViroContactRow(
                                name = contact.effectiveDisplayName,
                                formattedPhone = contact.phoneE164?.let {
                                    PhoneNumberFormatter.formatE164International(it)
                                },
                                showViroBadge = contact.isReachable,
                                imageUrl = contact.resolveAvatarUrl(),
                                onRowClick = { onContactDetail(contact) },
                                onCall = { onCallContact(contact) },
                                onMessage = null,
                                onMore = { onContactDetail(contact) },
                            )
                        }
                    }
                    Spacer(Modifier.height(ViroSpacing.md))
                }

                HomeSectionHeader(
                    title = "Favorites",
                    showSeeAll = allFavorites.size > HomeFavoritesPreviewCount,
                    expanded = favoritesExpanded,
                    onToggleSeeAll = { favoritesExpanded = !favoritesExpanded },
                )
                Spacer(Modifier.height(ViroSpacing.sm))
                if (allFavorites.isEmpty()) {
                    Text(
                        "Pin favorites from Contacts to reach them quickly.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (favoritesExpanded) {
                    HomeFavoritesExpandedSection(
                        favorites = allFavorites,
                        onOpenDetail = onContactDetail,
                        onCall = onCallContact,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        allFavorites.take(HomeFavoritesPreviewCount).forEach { contact ->
                            HomeFavoriteItem(
                                contact = contact,
                                onOpenDetail = { onContactDetail(contact) },
                                onCall = { onCallContact(contact) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(ViroSpacing.lg))

                HomeSectionHeader(
                    title = "Recent",
                    showSeeAll = recents.size > HomeRecentsPreviewCount,
                    expanded = recentsExpanded,
                    onToggleSeeAll = { recentsExpanded = !recentsExpanded },
                )
                Spacer(Modifier.height(ViroSpacing.xs))
                val recentDisplay = if (recentsExpanded) recents else recents.take(HomeRecentsPreviewCount)
                if (recentDisplay.isEmpty()) {
                    ViroEmptyState(
                        title = "No recent calls",
                        message = "Calls you make or receive will appear here.",
                    )
                } else {
                    HomeRecentsSection(
                        entries = recentDisplay,
                        contactsByPhone = contactsByPhone,
                        contactsByUserId = contactsByUserId,
                        session = session,
                        scope = scope,
                        onViewCallLog = onViewCallLog,
                        onCallContact = onCallContact,
                        onMessageCallLog = onMessageCallLog,
                        onOpenDialer = onOpenDialer,
                    )
                }
                Spacer(Modifier.height(HomeFabScrollPadding))
            }
        }
    }
}

@Composable
private fun HomeSectionHeader(
    title: String,
    showSeeAll: Boolean,
    expanded: Boolean,
    onToggleSeeAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = ViroColors.textPrimary,
        )
        if (showSeeAll) {
            TextButton(onClick = onToggleSeeAll) {
                Text(
                    if (expanded) "Show less" else "See all",
                    color = ViroColors.ElectricBlue,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun HomeFavoritesExpandedSection(
    favorites: List<ContactListItem>,
    onOpenDetail: (ContactListItem) -> Unit,
    onCall: (ContactListItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ViroSpacing.md)) {
        favorites.chunked(4).forEach { rowContacts ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                rowContacts.forEach { contact ->
                    HomeFavoriteItem(
                        contact = contact,
                        onOpenDetail = { onOpenDetail(contact) },
                        onCall = { onCall(contact) },
                    )
                }
                repeat(4 - rowContacts.size) {
                    Spacer(Modifier.widthIn(min = 68.dp, max = 84.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeRecentsSection(
    entries: List<CallLogEntry>,
    contactsByPhone: Map<String, ContactListItem>,
    contactsByUserId: Map<String, ContactListItem>,
    session: SessionManager,
    scope: kotlinx.coroutines.CoroutineScope,
    onViewCallLog: (CallLogEntry) -> Unit,
    onCallContact: (ContactListItem) -> Unit,
    onMessageCallLog: (CallLogEntry) -> Unit,
    onOpenDialer: () -> Unit,
) {
    entries.forEach { entry ->
        // Phone-keyed first (device contacts), falling back to the userId a
        // server-synced entry actually carries — see ServerCallHistoryMapper.
        val contact = entry.phoneE164?.let { contactsByPhone[it] }
            ?: entry.peerUserId?.let { contactsByUserId[it] }
        ViroCallLogRow(
            name = contact?.effectiveDisplayName ?: entry.name,
            statusLabel = callLogStatusLabel(entry.type),
            direction = entry.type.toCallLogDirection(),
            time = formatCallLogTime(entry.timestampMs),
            duration = "",
            isMissed = callLogIsFailure(entry.type),
            isGroup = entry.type == CallLogType.GROUP,
            imageUrl = contact?.resolveAvatarUrl(),
            messageInOverflow = true,
            horizontalPadding = 0.dp,
            onViewContact = { onViewCallLog(entry) },
            // Prefer the freshly resolved contact (same one used for the name/photo
            // above) over the call log entry's own fields — a server-synced entry
            // never carries phoneE164, and its peerUserId can predate a match that
            // only landed later, so falling back to those stale values silently
            // broke calling/messaging back.
            onCallBack = {
                val phone = contact?.phoneE164 ?: entry.phoneE164
                val userId = contact?.userId ?: entry.peerUserId
                when {
                    phone != null || userId != null -> onCallContact(
                        ContactListItem(
                            id = entry.id,
                            displayName = entry.name,
                            phoneE164 = phone,
                            userId = userId,
                        ),
                    )
                    else -> onOpenDialer()
                }
            },
            onMessage = {
                onMessageCallLog(
                    entry.copy(
                        phoneE164 = contact?.phoneE164 ?: entry.phoneE164,
                        peerUserId = contact?.userId ?: entry.peerUserId,
                    ),
                )
            },
            onDelete = { session.callHistoryStore.deleteEntry(entry.id) },
            onToggleFavorite = entry.phoneE164?.let { phone ->
                {
                    scope.launch {
                        session.contactsRepository.findByPhone(phone)?.let {
                            session.contactsRepository.setFavorite(it.id, true)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun HomeFavoriteItem(
    contact: ContactListItem,
    onOpenDetail: () -> Unit,
    onCall: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 68.dp, max = 84.dp),
    ) {
        ViroAvatar(
            displayName = contact.effectiveDisplayName,
            imageUrl = contact.resolveAvatarUrl(),
            size = ViroAvatarSize.Medium,
            modifier = Modifier.clickable(onClick = onOpenDetail),
        )
        Spacer(Modifier.height(ViroSpacing.xs))
        Text(
            contact.effectiveDisplayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = ViroColors.textPrimary,
            modifier = Modifier.clickable(onClick = onOpenDetail),
        )
        IconButton(
            onClick = onCall,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Default.Call,
                contentDescription = "Call ${contact.effectiveDisplayName}",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
