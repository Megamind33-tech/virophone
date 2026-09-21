package com.viroreach.app.consumer.messages

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.app.consumer.ChatRoute
import com.viroreach.app.consumer.resolveAvatarUrl
import com.viroreach.app.messaging.ConversationItem
import com.viroreach.app.messaging.Delivery
import com.viroreach.app.messaging.preview
import com.viroreach.app.messaging.ui.ChatLock
import com.viroreach.app.messaging.ui.GroupAvatar
import com.viroreach.app.messaging.ui.Ticks
import com.viroreach.app.relationships.ui.ConnectionsDashboard
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroAvatar
import com.viroreach.core.designsystem.components.ViroAvatarSize
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import com.viroreach.core.network.RelationshipDto
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class PeerInfo(val name: String, val avatarUrl: String?, val phone: String?)

/**
 * Messages and Connections. The inbox is still chronological, but each chat
 * carries the one piece of relationship context that matters right now —
 * "Evening Loop waiting", "Follow-up due", "Birthday in 2 days".
 */
@Composable
fun MessagesInboxScreen(
    session: SessionManager,
    onOpenChat: (ChatRoute) -> Unit,
    onCall: (peerUserId: String?, phone: String?, name: String) -> Unit,
    onOpenRelationship: (peerUserId: String?, phone: String?, name: String) -> Unit,
    startOnConnections: Boolean = false,
    onSearch: () -> Unit = {},
    onNewGroup: () -> Unit = {},
    showMoments: Boolean = false,
) {
    var tab by rememberSaveable { mutableIntStateOf(if (startOnConnections) 1 else 0) }
    LaunchedEffect(startOnConnections) { if (startOnConnections) tab = 1 }
    ViroScreenBackground {
        ViroSafeScreen {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ViroSpacing.md, vertical = ViroSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showMoments) Text("Viro", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
                    else Segmented(listOf("Messages", "Connections"), tab) { tab = it }
                    Spacer(Modifier.weight(1f))
                    if (showMoments || tab == 0) {
                        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search messages", tint = Color.White) }
                        IconButton(onClick = onNewGroup) { Icon(Icons.Default.GroupAdd, "New group", tint = Color.White) }
                    }
                }
                if (showMoments || tab == 0) {
                    Inbox(session, onOpenChat, if (showMoments) ({ com.viroreach.app.moments.MomentsHeader(session, onCall) }) else null)
                } else {
                    ConnectionsDashboard(session, onOpenChat, onCall, onOpenRelationship)
                }
            }
        }
    }
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(ViroColors.NavySurfaceElevated).padding(3.dp),
    ) {
        labels.forEachIndexed { i, label ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(17.dp))
                    .background(if (i == selected) ViroColors.accent else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(label, color = Color.White, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Inbox(session: SessionManager, onOpenChat: (ChatRoute) -> Unit, header: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversations by remember { session.messaging.conversations() }.collectAsState(initial = emptyList())
    val typing by session.messaging.typing.collectAsState()
    val overview by session.relationships.overview.collectAsState()
    var peers by remember { mutableStateOf<Map<String, PeerInfo>>(emptyMap()) }
    var showHidden by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var optionsFor by remember { mutableStateOf<ConversationItem?>(null) }
    var syncing by remember { mutableStateOf(false) }
    // Whether a backup is waiting for this account. Only worth asking once,
    // and only worth showing while there is nothing here.
    var backupWaiting by remember { mutableStateOf(false) }
    var restoreOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        backupWaiting = runCatching { session.backup.state().exists }.getOrDefault(false)
    }
    if (restoreOpen) {
        com.viroreach.app.consumer.ChatBackupDialog(session, startInRestore = true) { restoreOpen = false }
    }

    LaunchedEffect(Unit) {
        syncing = true
        session.messaging.syncNow()
        session.relationships.refresh()
        syncing = false
    }
    LaunchedEffect(conversations.map { it.peerUserId to it.lastSender }.toSet()) {
        val map = mutableMapOf<String, PeerInfo>()
        for (c in conversations) {
            if (c.isGroup) {
                c.lastSender?.takeIf { it !in map }?.let { sender ->
                    map[sender] = PeerInfo(session.contactsRepository.nameForUserId(sender) ?: "Someone", null, null)
                }
                continue
            }
            val id = c.peerUserId ?: continue
            val contact = session.contactsRepository.findByUserId(id)
            map[id] = PeerInfo(
                name = contact?.effectiveDisplayName ?: session.contactsRepository.nameForUserId(id) ?: "Viro user",
                avatarUrl = contact?.resolveAvatarUrl(),
                phone = contact?.phoneE164,
            )
        }
        peers = map
    }

    fun open(c: ConversationItem) {
        val p = c.peerUserId?.let { peers[it] }
        val go = {
            onOpenChat(
                ChatRoute(
                    conversationId = c.id,
                    peerName = if (c.isGroup) c.title ?: "Group" else p?.name ?: "Viro user",
                    peerUserId = c.peerUserId,
                    peerPhoneE164 = p?.phone,
                    peerAvatarUrl = p?.avatarUrl,
                ),
            )
        }
        if (c.locked) {
            ChatLock.authenticate(context, "Open chat with ${p?.name ?: "this person"}", onSuccess = go, onFailure = {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            })
        } else go()
    }

    val inThisList = conversations.filter {
        it.hidden == showHidden && it.archived == showArchived && (it.lastMessageId != null || it.isPrivate)
    }
    // Pinned chats sit above the rest, newest pin first; everything else stays
    // in most-recent order.
    val visible = remember(inThisList) {
        val (pinned, rest) = inThisList.partition { it.pinnedAt != null }
        pinned.sortedByDescending { it.pinnedAt } + rest
    }
    val hiddenCount = conversations.count { it.hidden }
    val archivedCount = conversations.count { it.archived && !it.hidden && (it.lastMessageId != null || it.isPrivate) }
    val archivedUnread = conversations.filter { it.archived && !it.hidden }.sumOf { it.unread }

    Column(Modifier.fillMaxSize()) {
        if (syncing && conversations.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ViroColors.accent)
        if (hiddenCount > 0 || showHidden) {
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (showHidden) showHidden = false
                    else ChatLock.authenticate(context, "Show hidden chats", onSuccess = { showHidden = true }, onFailure = {
                        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                    })
                }.padding(horizontal = ViroSpacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (showHidden) Icons.Default.ArrowBack else Icons.Default.VisibilityOff, null, tint = ViroColors.textSecondary)
                Spacer(Modifier.width(12.dp))
                Text(if (showHidden) "Back to chats" else "Hidden chats ($hiddenCount)", color = ViroColors.textSecondary)
            }
        }
        // if/else, never an early `return@Column`: the Compose compiler (1.5.x)
        // emits one group end too many on an early return out of an inline
        // layout lambda, and the next recomposition that flips the branch
        // crashes in Stack.pop (IndexOutOfBoundsException: Index -1).
        if (showArchived || (archivedCount > 0 && !showHidden)) {
            Row(
                Modifier.fillMaxWidth().clickable { showArchived = !showArchived }
                    .padding(horizontal = ViroSpacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (showArchived) Icons.Default.ArrowBack else Icons.Default.Archive,
                    null,
                    tint = ViroColors.textSecondary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (showArchived) "Back to chats" else "Archived ($archivedCount)",
                    color = ViroColors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (!showArchived && archivedUnread > 0) {
                    Text("$archivedUnread", color = ViroColors.accent, fontSize = 13.sp)
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (header != null) item(key = "moments-header") { header() }
            if (visible.isEmpty()) {
                item(key = "empty-conversations") {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("💬", fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                showHidden -> "No hidden chats."
                                showArchived -> "No archived chats."
                                else -> "No conversations yet.\nMessage someone from Contacts or after a call."
                            },
                            color = ViroColors.textSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        // A new phone lands here with nothing, which is exactly where
                        // someone needs to be told their chats can come back.
                        if (!showHidden && !showArchived && backupWaiting) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "There is a backup of your chats. You will need the recovery key " +
                                    "from your old phone.",
                                color = ViroColors.textSecondary,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { restoreOpen = true }) { Text("Restore chats") }
                        }
                    }

                }
            } else {
                items(visible, key = { it.id }) { c ->
                    val p = c.peerUserId?.let { peers[it] }
                    val rel = overview?.relationships?.firstOrNull { it.subjectUserId != null && it.subjectUserId == c.peerUserId }
                    ConversationRow(
                        c = c,
                        name = if (c.isGroup) c.title ?: "Group" else p?.name ?: "Viro user",
                        lastSenderName = if (c.isGroup && !c.lastMine) c.lastSender?.let { peers[it]?.name?.substringBefore(' ') } else null,
                        avatarUrl = p?.avatarUrl,
                        relationship = rel,
                        typingState = typing[c.id]?.state,
                        onClick = { open(c) },
                        onLongClick = { optionsFor = c },
                    )
                }

            }
        }
    }

    optionsFor?.let { c ->
        val name = c.peerUserId?.let { peers[it]?.name } ?: "this chat"
        AlertDialog(
            onDismissRequest = { optionsFor = null },
            title = { Text(name) },
            text = {
                Column {
                    @Composable
                    fun opt(label: String, action: () -> Unit) = Text(label, modifier = Modifier.fillMaxWidth().clickable {
                        optionsFor = null
                        action()
                    }.padding(vertical = 12.dp))
                    opt(if (c.pinnedAt != null) "Unpin from top" else "Pin to top") {
                        scope.launch { session.messaging.setPinned(c.id, c.pinnedAt == null) }
                    }
                    opt(if (c.archived) "Unarchive" else "Archive") {
                        scope.launch { session.messaging.setArchived(c.id, !c.archived) }
                    }
                    if (c.unread == 0 && !c.unreadMarked) {
                        opt("Mark as unread") { scope.launch { session.messaging.markUnread(c.id) } }
                    }
                    opt(if (c.hidden) "Unhide chat" else "Hide chat") { scope.launch { session.messaging.setHidden(c.id, !c.hidden) } }
                    opt(if (c.muted) "Unmute" else "Mute for 8 hours") {
                        scope.launch { session.messaging.setMuted(c.id, if (c.muted) null else System.currentTimeMillis() + 8 * 3600_000L) }
                    }
                    opt(if (c.locked) "Unlock chat" else "Lock chat") {
                        ChatLock.authenticate(context, if (c.locked) "Unlock chat" else "Lock chat", onSuccess = {
                            scope.launch { session.messaging.setLocked(c.id, !c.locked) }
                        })
                    }
                    opt("Delete chat") { scope.launch { session.messaging.clearForMe(c.id) } }
                }
            },
            confirmButton = { TextButton(onClick = { optionsFor = null }) { Text("Close") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    c: ConversationItem,
    name: String,
    lastSenderName: String? = null,
    avatarUrl: String?,
    relationship: RelationshipDto?,
    typingState: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = ViroSpacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            if (c.isGroup) GroupAvatar(name, 48.dp)
            else ViroAvatar(displayName = name, imageUrl = avatarUrl, size = ViroAvatarSize.Medium)
            if (c.isPrivate) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(ViroColors.NavyBackground),
                    contentAlignment = Alignment.Center,
                ) { Text("🔒", fontSize = 10.sp) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (c.isPrivate) "$name · Private" else name,
                    color = Color.White,
                    fontWeight = if (c.unread > 0 || c.unreadMarked || c.mentionedUnread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (c.muted) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.NotificationsOff, "Muted", tint = ViroColors.textSecondary, modifier = Modifier.size(14.dp))
                }
                if (c.locked) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Lock, "Locked", tint = ViroColors.textSecondary, modifier = Modifier.size(14.dp))
                }
                if (c.pinnedAt != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.PushPin, "Pinned", tint = ViroColors.textSecondary, modifier = Modifier.size(14.dp))
                }
            }
            relationshipHint(relationship, c)?.let { hint ->
                Text(hint, color = ViroColors.accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    typingState != null -> Text(
                        if (typingState == "recording") "recording voice…" else "typing…",
                        color = ViroColors.accent, fontSize = 14.sp,
                    )
                    c.locked -> Text("Locked chat", color = ViroColors.textSecondary, fontSize = 14.sp)
                    else -> {
                        if (c.lastMine && !c.lastDeleted && c.lastType != "SYSTEM") {
                            Ticks(
                                when {
                                    c.lastPending == "FAILED" -> Delivery.FAILED
                                    c.lastPending != null -> Delivery.SENDING
                                    c.lastAt != null && (c.peerLastReadAt ?: 0) >= c.lastAt -> Delivery.READ
                                    c.lastAt != null && (c.peerLastDeliveredAt ?: 0) >= c.lastAt -> Delivery.DELIVERED
                                    else -> Delivery.SENT
                                },
                                tint = ViroColors.textSecondary,
                            )
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(
                            when {
                                c.lastType == "SYSTEM" -> ""
                                c.lastMine -> "You: "
                                lastSenderName != null -> "$lastSenderName: "
                                else -> ""
                            } + c.preview(),
                            color = ViroColors.textSecondary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            (c.lastAt ?: c.updatedAt).let {
                Text(inboxTime(it), color = if (c.unread > 0) ViroColors.accent else ViroColors.textMuted, fontSize = 12.sp)
            }
            if (c.mentionedUnread) {
                // Being named is worth more than a count: it says why to look.
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("@", color = ViroColors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (c.unread > 0) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            Modifier.clip(CircleShape).background(ViroColors.accent).padding(horizontal = 7.dp, vertical = 2.dp),
                        ) { Text(c.unread.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            } else if (c.unread > 0) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.clip(CircleShape).background(ViroColors.accent).padding(horizontal = 7.dp, vertical = 2.dp),
                ) { Text(c.unread.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            } else if (c.unreadMarked) {
                // Marked unread by hand: a plain dot, with no count to show.
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(ViroColors.accent))
            }
        }
    }
}

/** The single most useful line of context for this chat, or nothing. */
private fun relationshipHint(rel: RelationshipDto?, c: ConversationItem): String? {
    if (c.isPrivate) {
        val left = (c.expiresAt ?: return null) - System.currentTimeMillis()
        val mins = (left / 60_000).coerceAtLeast(0)
        return "⏳ Ends in " + if (mins < 60) "$mins min" else "${mins / 60} h"
    }
    rel ?: return null
    val icon = rel.icon ?: "❤️"
    val flag = rel.flags.orEmpty().firstOrNull() ?: return null
    return when (flag.code) {
        "LOOP_WAITING" -> "$icon ${rel.loops.orEmpty().firstOrNull { it.waitingOnMe == true }?.title ?: "Loop"} waiting"
        "COMMITMENT_DUE" -> "📌 ${rel.openCommitments.orEmpty().firstOrNull()?.text?.replaceFirstChar { it.uppercase() } ?: "Commitment"} due"
        "FOLLOW_UP_DUE" -> "💼 Follow-up due"
        "DUE_TODAY" -> "$icon Check-in due today"
        "NEEDS_ATTENTION" -> "$icon Needs attention"
        "DATE_SOON" -> rel.nextDate?.let { d ->
            val emoji = if (d.kind.contains("BIRTHDAY")) "🎉" else icon
            "$emoji ${d.label} " + when (d.daysAway) {
                0 -> "today"
                1 -> "tomorrow"
                else -> "in ${d.daysAway} days"
            }
        }
        else -> null
    }
}

private fun inboxTime(ms: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = then.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    val withinWeek = now.timeInMillis - ms < 6 * 86_400_000L
    return when {
        sameDay -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        isYesterday -> "Yesterday"
        withinWeek -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))
    }
}
