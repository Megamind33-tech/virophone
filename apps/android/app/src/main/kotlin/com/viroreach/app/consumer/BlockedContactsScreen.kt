package com.viroreach.app.consumer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroBackButton
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import kotlinx.coroutines.launch

/**
 * One row in the blocked or spam list. [contactId] is present when this device
 * knows the contact, which is what allows unblocking by phone number; [userId]
 * is present when they have a Viro account, which is what the server-side block
 * is keyed on. Either can be null, and a row needs only one of them.
 */
private data class RestrictedEntry(
    val key: String,
    val name: String,
    val contactId: String?,
    val userId: String?,
)

/**
 * Blocked and spam-marked contacts.
 *
 * This screen used to read only the server's user-id block list, which meant a
 * blocked contact who had never joined Viro was invisible here: there was no
 * server row for them, because the blocks table is keyed by blocked_user_id.
 * Blocking such a contact appeared to do nothing at all. It now merges both
 * sources — server-side user blocks and phone-number blocks held against the
 * account — so every block a user makes is visible and reversible.
 */
@Composable
fun BlockedContactsScreen(
    session: SessionManager,
    onBack: () -> Unit,
) {
    var blocked by remember { mutableStateOf<List<RestrictedEntry>>(emptyList()) }
    var spam by remember { mutableStateOf<List<RestrictedEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        error = null

        val localBlocked = runCatching { session.contactsRepository.blockedContacts() }
            .getOrDefault(emptyList())
        val localSpam = runCatching { session.contactsRepository.spamContacts() }
            .getOrDefault(emptyList())
        val serverBlocked = runCatching { session.api.listBlocks() }
            .getOrElse {
                // A failed server fetch must not hide the local blocks: showing
                // nothing would suggest the user has blocked no one.
                error = "Couldn't reach the server — showing blocks saved on this phone."
                emptyList()
            }

        val rows = mutableListOf<RestrictedEntry>()
        val seenUserIds = mutableSetOf<String>()

        localBlocked.forEach { c ->
            c.userId?.let { seenUserIds.add(it) }
            rows.add(
                RestrictedEntry(
                    key = c.id,
                    name = c.effectiveDisplayName,
                    contactId = c.id,
                    userId = c.userId,
                ),
            )
        }
        // Blocks the server knows about that this device has no contact for —
        // blocked from another phone, or the contact has since been deleted.
        serverBlocked.map { it.blockedUserId }
            .filter { it !in seenUserIds }
            .forEach { userId ->
                rows.add(
                    RestrictedEntry(
                        key = userId,
                        name = PeerNameResolver.resolveBlocking(session = session, userId = userId),
                        contactId = null,
                        userId = userId,
                    ),
                )
            }

        blocked = rows
        spam = localSpam.map {
            RestrictedEntry(
                key = it.id,
                name = it.effectiveDisplayName,
                contactId = it.id,
                userId = it.userId,
            )
        }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(Modifier.fillMaxSize().padding(ViroSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViroBackButton(onClick = onBack)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Blocked & spam",
                        style = MaterialTheme.typography.titleLarge,
                        color = ViroColors.textPrimary,
                    )
                }
                Spacer(Modifier.height(ViroSpacing.md))

                error?.let {
                    Text(it, color = ViroColors.consumerWarning, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(ViroSpacing.sm))
                }

                if (loading) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ViroColors.accent)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(ViroSpacing.sm)) {
                        item {
                            SectionHeading("Blocked", blocked.size)
                        }
                        if (blocked.isEmpty()) {
                            item {
                                Text(
                                    "You haven't blocked anyone.",
                                    color = ViroColors.textSecondary,
                                )
                            }
                        }
                        items(blocked, key = { "b-" + it.key }) { entry ->
                            RestrictedRow(
                                name = entry.name,
                                // A Viro user is refused server-side: they cannot
                                // call or message this account at all. Someone not
                                // on Viro has nothing to be refused yet, so the
                                // block is held ready for if they join.
                                subtitle = if (entry.userId != null) {
                                    "On Viro — calls and messages refused"
                                } else {
                                    "Not on Viro yet — will apply if they join"
                                },
                                actionLabel = "Unblock",
                                onAction = {
                                    scope.launch {
                                        if (entry.contactId != null) {
                                            session.contactsRepository.unblockContact(entry.contactId)
                                        } else if (entry.userId != null) {
                                            runCatching { session.api.unblockUser(entry.userId) }
                                        }
                                        reload()
                                    }
                                },
                            )
                        }

                        item {
                            Spacer(Modifier.height(ViroSpacing.md))
                            SectionHeading("Marked as spam", spam.size)
                        }
                        if (spam.isEmpty()) {
                            item {
                                Text(
                                    "Nothing marked as spam. Mark a contact as spam to " +
                                        "flag their calls without blocking them.",
                                    color = ViroColors.textSecondary,
                                )
                            }
                        }
                        items(spam, key = { "s-" + it.key }) { entry ->
                            RestrictedRow(
                                name = entry.name,
                                subtitle = if (entry.userId != null) "On Viro" else "Not on Viro",
                                actionLabel = "Not spam",
                                onAction = {
                                    scope.launch {
                                        entry.contactId?.let {
                                            session.contactsRepository.setSpam(it, false)
                                        }
                                        reload()
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

@Composable
private fun SectionHeading(title: String, count: Int) {
    Text(
        if (count > 0) "$title ($count)" else title,
        style = MaterialTheme.typography.titleMedium,
        color = ViroColors.textPrimary,
    )
}

@Composable
private fun RestrictedRow(
    name: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = ViroColors.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(ViroSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = ViroColors.textPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    subtitle,
                    color = ViroColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onAction) {
                Text(actionLabel, color = ViroColors.accent)
            }
        }
    }
}
