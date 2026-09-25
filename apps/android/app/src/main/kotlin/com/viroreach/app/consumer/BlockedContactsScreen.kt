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
import com.viroreach.core.designsystem.components.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.viroreach.core.designsystem.ViroSpacing
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

    val unblock: (RestrictedEntry) -> Unit = { entry ->
        scope.launch {
            if (entry.contactId != null) {
                session.contactsRepository.unblockContact(entry.contactId)
            } else if (entry.userId != null) {
                runCatching { session.api.unblockUser(entry.userId) }
            }
            reload()
        }
    }
    val notSpam: (RestrictedEntry) -> Unit = { entry ->
        scope.launch {
            entry.contactId?.let { session.contactsRepository.setSpam(it, false) }
            reload()
        }
    }

    ViroSubScreen(title = "Blocked and spam", onBack = onBack) {
        error?.let { ViroStatusLine(it) }
        if (loading) {
            ViroLoadingState()
        } else {
            ViroSection(
                title = if (blocked.isEmpty()) "Blocked" else "Blocked · ${blocked.size}",
                footer = "Blocked people on Viro can't call or message you. Someone not on Viro yet is blocked the moment they join.",
            ) {
                if (blocked.isEmpty()) {
                    ViroListRow("You haven't blocked anyone", icon = Icons.Outlined.Block, enabled = false)
                }
                blocked.forEachIndexed { index, entry ->
                    if (index > 0) ViroRowDivider()
                    RestrictedRow(
                        name = entry.name,
                        // A Viro user is refused server-side: they cannot call
                        // or message this account at all. Someone not on Viro
                        // has nothing to be refused yet, so the block is held
                        // ready for if they join.
                        subtitle = if (entry.userId != null) "On Viro · calls and messages refused" else "Not on Viro yet",
                        actionLabel = "Unblock",
                        onAction = { unblock(entry) },
                    )
                }
            }
            ViroSection(
                title = if (spam.isEmpty()) "Marked as spam" else "Marked as spam · ${spam.size}",
                footer = "Marking someone as spam flags their calls without blocking them.",
            ) {
                if (spam.isEmpty()) {
                    ViroListRow("Nothing marked as spam", icon = Icons.Outlined.Report, enabled = false)
                }
                spam.forEachIndexed { index, entry ->
                    if (index > 0) ViroRowDivider()
                    RestrictedRow(
                        name = entry.name,
                        subtitle = if (entry.userId != null) "On Viro" else "Not on Viro",
                        actionLabel = "Not spam",
                        onAction = { notSpam(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RestrictedRow(
    name: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    ViroListRow(
        title = name,
        subtitle = subtitle,
        leading = { ViroAvatar(size = ViroAvatarSize.Small, displayName = name) },
        trailing = { TextButton(onClick = onAction) { Text(actionLabel, color = ViroColors.accent) } },
    )
}
