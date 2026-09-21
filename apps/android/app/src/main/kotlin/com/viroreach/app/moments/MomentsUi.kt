package com.viroreach.app.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.viroreach.app.personalization.UserProfile
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.components.*
import com.viroreach.core.network.CreateMomentBody
import com.viroreach.core.network.MomentDto
import com.viroreach.core.network.MomentMessageDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val types = listOf("FREE" to "Free", "BREAK" to "Break", "LISTENING" to "Listening",
    "WATCHING" to "Watching", "GAMING" to "Gaming", "WORKING" to "Working", "CUSTOM" to "Custom")
val momentReactions = listOf("❤️", "😂", "🔥", "👏", "👍")

/** Consumer surfaces use Viro's navy palette in both app theme modes. Keep
 *  Material controls legible on those surfaces when the system uses light mode. */
@Composable
fun MomentsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = ViroColors.BlueAccent,
        onPrimary = ViroColors.NavyBackground,
        background = ViroColors.background,
        onBackground = ViroColors.textPrimary,
        surface = ViroColors.surface,
        onSurface = ViroColors.textPrimary,
        surfaceVariant = ViroColors.surfaceRaised,
        onSurfaceVariant = ViroColors.textMuted,
        secondaryContainer = ViroColors.surfaceRaised,
        onSecondaryContainer = ViroColors.textPrimary,
        outline = ViroColors.textMuted,
    )) { content() }
}

/** NOW — the live-activity destination. Owns Moments only: conversations,
 *  calls and the contact directory each keep their own destination. */
@Composable
fun NowScreen(
    session: SessionManager,
    onCall: (String?, String?, String) -> Unit,
    onOpenChat: (userId: String?, name: String) -> Unit,
) {
    val repo = session.moments
    val moments by repo.moments.collectAsState()
    val invitations by repo.invitations.collectAsState()
    val loaded by repo.loaded.collectAsState()
    val error by repo.error.collectAsState()
    val profile by session.profileRepository.profile.collectAsState(initial = UserProfile())
    val scope = rememberCoroutineScope()
    var clock by remember { mutableLongStateOf(repo.now()) }
    var create by rememberSaveable { mutableStateOf(false) }
    var manage by rememberSaveable { mutableStateOf<String?>(null) }
    var roomId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repo) {
        repo.refresh(); repo.refreshInvitations()
        var ticks = 0
        while (true) {
            delay(1000)
            repo.prune()
            clock = repo.now()
            if (++ticks % 30 == 0) { repo.refresh(); repo.refreshInvitations() }
        }
    }
    val me = session.tokenStore.getUserId()
    val own = moments.firstOrNull { it.creatorUserId == me }
    val peers = moments.filter { it.creatorUserId != me }

    MomentsTheme {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Now", color = ViroColors.textPrimary, style = MaterialTheme.typography.headlineMedium)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "mine") {
                    Surface(shape = RoundedCornerShape(16.dp), color = ViroColors.surfaceRaised,
                        modifier = Modifier.fillMaxWidth().clickable { if (own == null) create = true else manage = own.id }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ViroAvatar(size = ViroAvatarSize.Small, imageUrl = profile.effectivePhotoUrl, displayName = profile.displayName)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (own == null) "What are you up to?" else "Your Moment", color = ViroColors.textPrimary,
                                    fontWeight = FontWeight.SemiBold)
                                Text(own?.let { "${it.activity()} · ${remainingMinutes(it.endsAt(), clock)} min left" }
                                    ?: "Start a Moment for your people", color = ViroColors.textMuted,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text(if (own == null) "Start" else "Manage", color = ViroColors.BlueAccent,
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                if (invitations.isNotEmpty()) {
                    item(key = "invitations-title") {
                        Text("Invitations", color = ViroColors.textPrimary, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(invitations, key = { it.invitationId }) { invitation ->
                        val m = invitation.moment
                        Surface(shape = RoundedCornerShape(12.dp), color = ViroColors.surface, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { roomId = m.id }) {
                                    Text("${m.displayName} invited you", color = ViroColors.textMuted, style = MaterialTheme.typography.labelMedium)
                                    Text(m.activity(), color = ViroColors.textPrimary, fontWeight = FontWeight.Medium)
                                }
                                TextButton(onClick = { roomId = m.id }) { Text("Join", color = ViroColors.BlueAccent) }
                                IconButton(onClick = { scope.launch { repo.declineInvitation(invitation.invitationId) } },
                                    modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, "Dismiss invitation", tint = ViroColors.textMuted,
                                        modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                item(key = "active-title") {
                    Text("Active now", color = ViroColors.textPrimary, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                if (peers.isEmpty()) {
                    item(key = "empty") {
                        if (loaded) {
                            Surface(shape = RoundedCornerShape(12.dp), color = ViroColors.surface, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("It's quiet right now.", color = ViroColors.textPrimary, fontWeight = FontWeight.Medium)
                                    Text("Start a Moment and give your people something to join.",
                                        color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else {
                            Text("Checking what's happening…", color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(peers, key = { it.id }) { m ->
                    NowCard(m, clock, primaryLabel = if (m.allowVoice) "Knock" else "Join",
                        onPrimary = {
                            actionError = null
                            if (m.allowVoice) {
                                busy = true
                                scope.launch { repo.knock(m.id).onFailure { actionError = it.message }; busy = false }
                            } else {
                                roomId = m.id
                            }
                        },
                        onReact = { emoji ->
                            scope.launch { repo.cheer(m.id, emoji).onFailure { actionError = it.message } }
                        })
                }
                if (error != null) item(key = "error") {
                    TextButton(onClick = { scope.launch { repo.refresh() } }) { Text("Couldn't refresh · Retry", color = ViroColors.textMuted) }
                }
                if (actionError != null) item(key = "action-error") {
                    Text(actionError!!, color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (create) CreateMomentSheet(onDismiss = { create = false }, onStart = { body ->
            busy = true
            scope.launch {
                repo.create(body).onSuccess { create = false; roomId = it.id }.onFailure { actionError = it.message }
                busy = false
            }
        }, busy = busy, error = actionError)

        manage?.let { id ->
            val moment = moments.firstOrNull { it.id == id }
            LaunchedEffect(id) { repo.refresh() }
            MomentPage("Your Moment", onBack = { manage = null; actionError = null }) {
                item(key = "manage") {
                    if (moment == null) {
                        Text("This Moment is no longer available.", color = ViroColors.textPrimary)
                    } else {
                        MomentAvatar(moment)
                        Spacer(Modifier.height(16.dp))
                        Text(moment.displayName, color = ViroColors.textPrimary, style = MaterialTheme.typography.headlineSmall)
                        Text(moment.activity(), color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(vertical = 8.dp))
                        Text("Open for ${remainingMinutes(moment.endsAt(), clock)} min", color = ViroColors.textMuted)
                        Spacer(Modifier.height(24.dp))
                        // Changing the audience while the Moment is running.
                        // Phase 1 and 2 fixed it at creation, which meant the
                        // only way to narrow it was to end the Moment and
                        // start another — losing the room and everyone in it.
                        Text("Visible to", color = ViroColors.textMuted)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((value, label) in listOf(
                                "CONNECTIONS" to "Viro connections",
                                "CONTACTS" to "My contacts",
                            )) {
                                val chosen = moment.visibility == value
                                FilterChip(
                                    selected = chosen,
                                    enabled = !busy,
                                    onClick = {
                                        if (!chosen) {
                                            busy = true
                                            scope.launch {
                                                repo.setVisibility(id, value)
                                                    .onFailure { actionError = it.message }
                                                busy = false
                                            }
                                        }
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                        if (moment.visibilityChangedAt != null) {
                            // Said plainly, because narrowing does not unsay
                            // anything: whoever was in the room has already
                            // read what was said while they were there.
                            Text(
                                "Changed while this Moment was running. People who can no longer " +
                                    "see it lose it from Now, but they saw what was said while they were here.",
                                color = ViroColors.textMuted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        Button(enabled = !busy, onClick = { roomId = id; manage = null },
                            modifier = Modifier.fillMaxWidth()) { Text("Open room") }
                        OutlinedButton(enabled = !busy, onClick = { busy = true; scope.launch {
                            repo.extend(id).onFailure { actionError = it.message }; busy = false
                        } }, modifier = Modifier.fillMaxWidth()) { Text("Extend 15 min") }
                        Button(enabled = !busy, onClick = { busy = true; scope.launch {
                            repo.end(id).onSuccess { manage = null }.onFailure { actionError = it.message }; busy = false
                        } }, modifier = Modifier.fillMaxWidth()) { Text("End Moment") }
                        if (actionError != null) Text(actionError!!, color = ViroColors.textMuted, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }

    roomId?.let { id ->
        MomentRoomScreen(
            session = session,
            room = session.openMomentRoom(id),
            onBack = { roomId = null },
            onCall = onCall,
            onOpenChat = onOpenChat,
        )
    }
}

/** One compact card, one primary action (§15): avatar, name, activity, time or
 *  participants, and nothing else — conversations and calls live elsewhere. */
@Composable
private fun NowCard(
    m: MomentDto,
    clock: Long,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onReact: (String?) -> Unit = {},
) {
    Surface(shape = RoundedCornerShape(12.dp), color = ViroColors.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.clickable(onClick = onPrimary).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                MomentAvatar(m)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(m.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = ViroColors.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(m.activity(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall)
                    val people = m.participantCount ?: 0
                    Text(if (people > 1) "$people people" else "${remainingMinutes(m.endsAt(), clock)} min",
                        color = ViroColors.textMuted, style = MaterialTheme.typography.labelSmall)
                }
                Text(primaryLabel, color = ViroColors.BlueAccent, style = MaterialTheme.typography.labelLarge)
            }
            MomentReactionRow(m, onReact)
        }
    }
}

/**
 * Reacting to the Moment itself — the small thing to do when joining is the
 * big one. Someone posts that they are free and nobody wants a room yet;
 * without this there is no way to say "I saw that" at all.
 *
 * Counts sit next to the emoji that have any, ranked by the server with the
 * most-chosen first, and tapping your own takes it back.
 */
@Composable
private fun MomentReactionRow(m: MomentDto, onReact: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (emoji in momentReactions) {
            val count = m.reactions.firstOrNull { it.emoji == emoji }?.count ?: 0
            val mine = m.myReaction == emoji
            Surface(
                shape = RoundedCornerShape(50),
                color = if (mine) ViroColors.BlueAccent.copy(alpha = 0.18f) else ViroColors.surfaceRaised,
                modifier = Modifier.padding(end = 6.dp).clickable { onReact(if (mine) null else emoji) },
            ) {
                Text(
                    if (count > 0) "$emoji $count" else emoji,
                    color = if (mine) ViroColors.BlueAccent else ViroColors.textMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}


/** The room attached to a Moment: header (who, what, how long, how many),
 *  Chat | People, and nothing the temporary interaction doesn't need (§6). */
@Composable
fun MomentRoomScreen(
    session: SessionManager,
    room: MomentRoomState,
    onBack: () -> Unit,
    onCall: (String?, String?, String) -> Unit,
    onOpenChat: (userId: String?, name: String) -> Unit,
) {
    val moment by room.moment.collectAsState()
    val participants by room.participants.collectAsState()
    val messages by room.messages.collectAsState()
    val closed by room.closed.collectAsState()
    val encrypted by room.encrypted.collectAsState()
    val error by room.error.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var invite by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val me = session.tokenStore.getUserId()
    LaunchedEffect(room) {
        room.enter()
        while (true) {
            delay(1000)
            clock = System.currentTimeMillis()
            if (!closed) room.refresh()
        }
    }
    // The room's own lifecycle frames arrive through the shared socket flow.
    LaunchedEffect(room) {
        session.moments.frames.collect { (type, payload) -> room.onFrame(type, payload) }
    }
    DisposableEffect(room) {
        onDispose { scope.launch { room.leave() } }
    }

    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = ViroColors.background) {
            if (closed) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This Moment has ended.", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text("Back to Now") }
                }
                LaunchedEffect(closed) { delay(2500); onBack() }
                return@Surface
            }
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = ViroColors.textPrimary) }
                    Column(Modifier.weight(1f)) {
                        Text(moment?.displayName ?: "Moment", color = ViroColors.textPrimary,
                            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // The lock is claimed only once something has
                        // actually been sealed in this room — a promise that
                        // appears before it is true is worse than none.
                        Text(
                            (if (encrypted) "🔒 " else "") +
                                (moment?.let { "${it.activity()} · ${remainingMinutes(it.endsAt(), clock)} min" } ?: "…"),
                            color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${participants.size}", color = ViroColors.textMuted,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 12.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    TabRow(selectedTabIndex = tab, containerColor = ViroColors.background) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chat", color = ViroColors.textPrimary) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("People", color = ViroColors.textPrimary) })
                    }
                }
                if (tab == 0) RoomChat(room, messages, me, actionError, onError = { actionError = it })
                else RoomPeople(moment, participants, me,
                    onMessage = onOpenChat,
                    onInvite = { invite = true },
                    onTalk = { m ->
                        scope.launch {
                            // Access is rechecked server-side right before the call.
                            session.moments.verify(m.id).onSuccess { fresh ->
                                onCall(fresh.creatorUserId, null, fresh.displayName)
                            }
                        }
                    })
            }

            if (invite && moment != null) {
                InviteSheet(session = session, momentId = moment!!.id, onDismiss = { invite = false },
                    onInvited = { scope.launch { room.refresh() } })
            }
        }
    }
}

@Composable
private fun RoomChat(
    room: MomentRoomState,
    messages: List<MomentMessageDto>,
    me: String?,
    actionError: String?,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var reactTo by remember { mutableStateOf<MomentMessageDto?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message, mine = message.senderUserId == me,
                    onReact = { reactTo = message })
            }
        }
        if (actionError != null) Text(actionError, color = ViroColors.textMuted,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = { draft = it.take(500) },
                placeholder = { Text("Message the room", color = ViroColors.textMuted) },
                modifier = Modifier.weight(1f), maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = ViroColors.textPrimary,
                    unfocusedTextColor = ViroColors.textPrimary,
                    focusedBorderColor = ViroColors.BlueAccent,
                    unfocusedBorderColor = ViroColors.textMuted))
            Spacer(Modifier.width(8.dp))
            Button(enabled = draft.isNotBlank(), onClick = {
                val text = draft; draft = ""
                scope.launch { room.send(text).onFailure { onError(it.message ?: "Couldn't send.") } }
            }) { Text("Send") }
        }
    }
    reactTo?.let { message ->
        AlertDialog(onDismissRequest = { reactTo = null }, containerColor = ViroColors.surface,
            title = { Text("React", color = ViroColors.textPrimary) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    momentReactions.forEach { emoji ->
                        TextButton(onClick = {
                            reactTo = null
                            scope.launch { room.react(message.id, emoji) }
                        }) { Text(emoji, style = MaterialTheme.typography.headlineMedium) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    val mine = message.reactions.any { r -> me in r.userIds }
                    reactTo = null
                    if (mine) scope.launch { room.react(message.id, null) }
                }) { Text("Remove mine", color = ViroColors.textMuted) }
            })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: MomentMessageDto, mine: Boolean, onReact: () -> Unit) {
    Column(Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine) Text(message.senderName, color = ViroColors.textMuted,
            style = MaterialTheme.typography.labelSmall)
        Surface(shape = RoundedCornerShape(12.dp),
            color = if (mine) ViroColors.BlueAccent else ViroColors.surfaceRaised,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onReact)) {
            // A sealed message this device could not open still has its place
            // in the room; the placeholder says so rather than showing a blank.
            Text(message.body ?: SEALED_UNREADABLE,
                color = if (mine) ViroColors.NavyBackground else ViroColors.textPrimary,
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(10.dp))
        }
        if (message.reactions.isNotEmpty()) {
            Text(message.reactions.joinToString("  ") { r -> "${r.emoji}${if (r.userIds.size > 1) r.userIds.size else ""}" },
                color = ViroColors.textMuted, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun RoomPeople(
    moment: MomentDto?,
    participants: List<com.viroreach.core.network.MomentParticipantDto>,
    me: String?,
    onMessage: (userId: String?, name: String) -> Unit,
    onInvite: () -> Unit,
    onTalk: (MomentDto) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (moment != null && moment.allowVoice && moment.creatorUserId != me) {
            Button(onClick = { onTalk(moment) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Talk to ${moment.displayName}") }
        }
        if (moment?.creatorUserId == me) {
            OutlinedButton(onClick = onInvite, modifier = Modifier.fillMaxWidth()) { Text("Invite connections") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(participants, key = { it.userId }) { participant ->
                Surface(shape = RoundedCornerShape(10.dp), color = ViroColors.surface,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = participant.userId != me) {
                        onMessage(participant.userId, participant.displayName)
                    }) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ViroAvatar(size = ViroAvatarSize.Small, imageUrl = null, displayName = participant.displayName)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(if (participant.userId == me) "You" else participant.displayName,
                                color = ViroColors.textPrimary, fontWeight = FontWeight.Medium)
                        }
                        if (participant.isHost) Text("Host", color = ViroColors.BlueAccent,
                            style = MaterialTheme.typography.labelMedium)
                        else if (participant.userId != me) Text("Message", color = ViroColors.textMuted,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Host-only invite sheet: accepted Viro connections, one tap, no groups (§13). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InviteSheet(session: SessionManager, momentId: String, onDismiss: () -> Unit, onInvited: () -> Unit) {
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(momentId) {
        loaded = false
        val all = runCatching { session.people.refreshConnections() }.getOrDefault(emptyList())
        connections = all.filter { it.status == "ACCEPTED" && !it.peerUserId.isNullOrBlank() }
            .map { it.peerUserId!! to (it.peerDisplayName?.takeIf { n -> n.isNotBlank() } ?: "Viro user") }
        loaded = true
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ViroColors.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Invite to your Moment", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if (error != null) Text(error!!, color = ViroColors.textMuted)
            if (loaded && connections.isEmpty() && error == null) {
                Text("No accepted Viro connections to invite yet.", color = ViroColors.textMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(connections, key = { it.first }) { (userId, name) ->
                    Surface(shape = RoundedCornerShape(10.dp), color = ViroColors.surfaceRaised,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = pending == null) {
                            pending = userId
                            scope.launch {
                                session.moments.invite(momentId, userId)
                                    .onSuccess { onInvited() }
                                    .onFailure { pending = null; error = it.message ?: "Couldn't invite." }
                            }
                        }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = ViroColors.textPrimary, modifier = Modifier.weight(1f))
                            Text(if (pending == userId) "Inviting…" else "Invite",
                                color = ViroColors.BlueAccent, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Listens for knocks anywhere in the app: the host answers from wherever
 *  they are, and accepting launches the existing call flow (§34). */
@Composable
fun MomentKnockListener(session: SessionManager, onCall: (String?, String?, String) -> Unit) {
    val repo = session.moments
    var knock by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repo) {
        repo.frames.collect { (type, payload) ->
            if (type == "moment.knock") knock = payload
        }
    }
    knock?.let { payload ->
        val knockerId = payload["knockerUserId"] as? String
        val knockerName = payload["knockerName"] as? String ?: "Someone"
        val momentId = payload["momentId"] as? String
        AlertDialog(onDismissRequest = { if (!busy) knock = null }, containerColor = ViroColors.surface,
            title = { Text("$knockerName wants to talk", color = ViroColors.textPrimary) },
            text = { Text("They knocked on your Moment.", color = ViroColors.textMuted) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        if (momentId != null && knockerId != null) {
                            session.moments.respondToKnock(momentId, knockerId, true)
                        }
                        knock = null; busy = false
                        if (knockerId != null) onCall(knockerId, null, knockerName)
                    }
                }) { Text("Accept", color = ViroColors.BlueAccent) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        if (momentId != null && knockerId != null) {
                            session.moments.respondToKnock(momentId, knockerId, false)
                        }
                        knock = null; busy = false
                    }
                }) { Text("Not now", color = ViroColors.textMuted) }
            })
    }
}

@Composable
private fun MomentAvatar(m: MomentDto) {
    Box(Modifier.border(1.dp, ViroColors.BlueAccent, CircleShape).padding(3.dp)) {
        ViroAvatar(size = ViroAvatarSize.Small, imageUrl = m.avatarUrl, displayName = m.displayName)
    }
}

@Composable
private fun MomentPage(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = ViroColors.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = ViroColors.textPrimary) }
                    Text(title, color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), content = content)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateMomentSheet(onDismiss: () -> Unit, onStart: (CreateMomentBody) -> Unit, busy: Boolean, error: String?) {
    var type by rememberSaveable { mutableStateOf("FREE") }
    var duration by rememberSaveable { mutableIntStateOf(15) }
    var audience by rememberSaveable { mutableStateOf("CONNECTIONS") }
    var text by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = ViroColors.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Start a Moment", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
                Text("A little time for your people.", color = ViroColors.textMuted)
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(types, key = { it.first }) { (value, label) ->
                        FilterChip(selected = type == value, onClick = {
                            type = value; duration = if (value == "FREE" || value == "BREAK") 15 else 30
                        }, label = { Text(label) })
                    }
                }
            }
            item {
                if (type == "CUSTOM" || type == "WATCHING" || type == "LISTENING" || type == "GAMING") {
                    OutlinedTextField(value = text, onValueChange = { text = it.take(60) },
                        label = { Text(if (type == "CUSTOM") "What are you up to?" else "Add a little detail (optional)") },
                        supportingText = { Text("${text.length}/60") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                }
            }
            item {
                Text("Duration", color = ViroColors.textPrimary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(1, 15, 30, 60, 120), key = { it }) { minutes ->
                        FilterChip(selected = duration == minutes, onClick = { duration = minutes }, label = { Text("$minutes min") })
                    }
                }
            }
            item {
                Text("Who can see this?", color = ViroColors.textPrimary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("CONNECTIONS" to "Viro connections", "CONTACTS" to "My contacts"), key = { it.first }) { (value, label) ->
                        FilterChip(selected = audience == value, onClick = { audience = value }, label = { Text(label) })
                    }
                }
                Text(if (audience == "CONNECTIONS") "Only accepted connections. Never public." else "Only people in your synced contacts. Never public.",
                    color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall)
            }
            item {
                if (error != null) Text(error, color = ViroColors.textMuted)
                Button(onClick = { onStart(CreateMomentBody(type, text.trim().takeIf { it.isNotEmpty() && type in listOf("CUSTOM", "WATCHING", "LISTENING", "GAMING") }, audience, duration)) },
                    enabled = !busy && (type != "CUSTOM" || text.isNotBlank()), modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Starting…" else "Start")
                }
            }
        }
    }
}
