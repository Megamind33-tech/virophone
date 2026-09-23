package com.viroreach.app.moments.engine

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.viroreach.core.designsystem.components.ViroAvatar
import com.viroreach.core.designsystem.components.ViroAvatarSize
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.HorizontalDivider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.viroreach.voice.webrtc.PresenceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viroreach.app.moments.InviteSheet
import com.viroreach.app.moments.MomentRoomState
import com.viroreach.app.moments.RoomPeople
import com.viroreach.app.moments.activity
import com.viroreach.app.moments.endsAt
import com.viroreach.app.moments.remainingMinutes
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.network.MomentDto
import com.viroreach.core.network.MomentChoiceBody
import com.viroreach.core.network.MomentRoomChangeBody
import com.viroreach.core.network.MomentTimerBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A Moment room: one living space that changes around what people are doing
 * together.
 *
 * The shell is always the same — a scene, the primary experience filling most
 * of the screen, the people felt around it, three or four things to do near
 * the thumb — and everything inside it is a module the server chose for the
 * whole room at once. Chat is a sheet that comes up when wanted and goes away
 * when not; it never takes the screen from the thing people came for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentRoomEngine(
    session: SessionManager,
    room: MomentRoomState,
    now: Long,
    onBack: () -> Unit,
    onEnd: () -> Unit,
    onCall: (String?, String?, String) -> Unit,
    onOpenChat: (userId: String?, name: String) -> Unit,
    live: MomentLive? = null,
    video: MomentVideo? = null,
    shared: MomentMediaShare? = null,
    exo: androidx.media3.exoplayer.ExoPlayer? = null,
) {
    val moment by room.moment.collectAsState()
    val participants by room.participants.collectAsState()
    val messages by room.messages.collectAsState()
    val runtime by room.runtime.collectAsState()
    val encrypted by room.encrypted.collectAsState()
    val scope = rememberCoroutineScope()
    val me = session.tokenStore.getUserId()
    val media by (live?.state ?: remember { MutableStateFlow(PresenceSnapshot()) }).collectAsState()
    val liveNotice by (live?.notice ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    val unavailable by (live?.unavailable ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    val context = LocalContext.current
    val sharedItems by room.media.collectAsState()
    val playerView by (shared?.playback?.view ?: remember { MutableStateFlow(SharedPlayerView()) }).collectAsState()

    var chatOpen by remember { mutableStateOf(false) }
    var peopleOpen by remember { mutableStateOf(false) }
    var togetherOpen by remember { mutableStateOf(false) }
    var inviteOpen by remember { mutableStateOf(false) }
    var timerOpen by remember { mutableStateOf(false) }
    var askOpen by remember { mutableStateOf(false) }
    var touchOpen by remember { mutableStateOf(false) }
    var sceneOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var chatError by remember { mutableStateOf<String?>(null) }
    // The remark being answered, if any. It lives here rather than in either
    // the comments or the composer because both need it: one to mark the
    // message, the other to send the link.
    var replyingTo by remember { mutableStateOf<com.viroreach.core.network.MomentMessageDto?>(null) }

    // Messages that arrived while the chat was closed: a quiet badge, never a
    // takeover of the room.
    var seenMessages by remember { mutableIntStateOf(messages.size) }
    LaunchedEffect(chatOpen, messages.size) { if (chatOpen) seenMessages = messages.size }
    val unread = (messages.size - seenMessages).coerceAtLeast(0)

    // Arrival, said the way a person would: "Natasha is here". Never a banner
    // over what everyone is doing.
    var arrival by remember { mutableStateOf<String?>(null) }
    var known by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(participants) {
        val ids = participants.map { it.userId }.toSet()
        val before = known
        if (before != null) {
            participants.firstOrNull { it.userId !in before && it.userId != me }?.let { newcomer ->
                arrival = "${newcomer.displayName.substringBefore(' ')} is here"
            }
        }
        known = ids
    }
    LaunchedEffect(arrival) { if (arrival != null) { delay(3_500); arrival = null } }
    LaunchedEffect(notice) { if (notice != null) { delay(3_000); notice = null } }
    // Something the live link did on its own - always something turned off -
    // is said once, in the room's own voice.
    LaunchedEffect(liveNotice) {
        val said = liveNotice
        if (said != null) { notice = said; delay(4_500); live?.notice?.value = null }
    }
    LaunchedEffect(unavailable) { unavailable?.let { notice = it } }

    // The camera and microphone are asked for when they are wanted, and only then.
    fun toggleCamera() {
        val l = live ?: return
        scope.launch { l.setCamera(!media.cameraOn) }
    }
    fun toggleMic() {
        val l = live ?: return
        scope.launch { l.setMicrophone(!media.micOn) }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) toggleCamera() else notice = "Viro needs the camera to show you. You can allow it in Settings."
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) toggleMic() else notice = "Viro needs the microphone for them to hear you. You can allow it in Settings."
    }
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val shape = runtime
    val scene = shape?.scene ?: "NEUTRAL"
    /**
     * Which module this phone is giving the room to, when its owner wants
     * something other than what the activity chose.
     *
     * Deliberately this phone's business and nobody else's. Whether the film
     * or the faces should be large is how one person wants to watch, not a
     * fact about the room — taking everyone else's layout away because you
     * wanted to see a face would be the wrong kind of shared. The scene is
     * shared because it is the room's atmosphere; this is not.
     */
    var focus by remember { mutableStateOf<String?>(null) }
    // A room that becomes something else starts again from what it chose.
    LaunchedEffect(shape?.primary) { focus = null }
    val primaryKey = focus ?: shape?.primary
    val besideKeys = remember(shape?.primary, shape?.secondary, focus) {
        buildList {
            shape?.secondary?.let { addAll(it) }
            // What was the room before the swap now sits beside it.
            if (focus != null) shape?.primary?.let { add(it) }
        }.filter { it != primaryKey }.distinct()
    }
    LaunchedEffect(shape?.primary) { shape?.primary?.let { live?.onRoomShape(it) } }

    // Something playing lifts the whole room: the light swells a little and
    // moves a little more. A room with music in it should not look like an
    // empty one.
    val playing by room.playback.collectAsState()
    val energy = if (playing?.status == "PLAYING") 1f else 0f

    Box(Modifier.fillMaxSize()) {
        MomentScene(scene, energy = energy, moodTint = MomentMood.of(moment?.mood)?.tint)

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            RoomHeader(
                moment = moment,
                intentLabel = MomentIntent.of(shape?.intent)?.label,
                encrypted = encrypted,
                now = now,
                onBack = onBack,
                menuOpen = menuOpen,
                onMenu = { menuOpen = it },
                isHost = moment?.creatorUserId == me,
                onPeople = { menuOpen = false; peopleOpen = true },
                onInvite = { menuOpen = false; inviteOpen = true },
                onTogether = { menuOpen = false; togetherOpen = true },
                onEnd = { menuOpen = false; onEnd() },
            )

            // The primary experience. When the room changes, this is what
            // transforms — the room, not a jump to another screen.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (shape != null) {
                    val ctx = MomentRoomContext(
                        room, moment, shape, participants, me, now,
                        media = media,
                        video = video,
                        flipCamera = { live?.flipCamera() },
                        player = shared?.let { MomentMediaContext(it, playerView, sharedItems, exo) },
                    )
                    AnimatedContent(
                        targetState = primaryKey,
                        transitionSpec = {
                            (fadeIn(tween(500)) + scaleIn(tween(500), initialScale = 0.94f)) togetherWith
                                (fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 1.04f))
                        },
                        label = "momentPrimary",
                    ) { primary ->
                        val module = primary?.let { MomentModules.find(it) }
                        if (module != null) {
                            module.Primary(ctx, Modifier.fillMaxSize())
                        } else {
                            // The server can be ahead of this phone. Say so plainly
                            // rather than draw an empty room.
                            UnsupportedHere()
                        }
                    }
                    // Everything that sits over the room sits in one column
                    // at the bottom of it. Two overlays both anchored to the
                    // bottom of the same box is how the remarks ended up
                    // printed through the music bar: neither knew the other
                    // was there. One column, in order, and they cannot.
                    Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RoomCommentsBar(
                            count = messages.size,
                            latest = messages.lastOrNull(),
                            me = me,
                            onOpen = { chatOpen = true },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                        val besides = besideKeys.mapNotNull { MomentModules.find(it) }
                        if (besides.isNotEmpty()) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                besides.forEach { it.Secondary(ctx, Modifier.fillMaxWidth()) }
                            }
                        }
                    }
                }
                TouchArrivals(room)
                androidx.compose.animation.AnimatedVisibility(
                    visible = arrival != null || notice != null,
                    enter = fadeIn() + slideInVertically { -it / 2 },
                    exit = fadeOut() + slideOutVertically { -it / 2 },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                ) {
                    Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.45f)) {
                        Text(
                            arrival ?: notice ?: "",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // Reading and saying something are the same place now. The box
            // used to slide up on its own with the remarks stacked over the
            // scene behind it, which meant the conversation was in two halves
            // and neither could be scrolled.
            if (chatOpen) {
                RoomCommentsSheet(
                    room = room,
                    messages = messages,
                    shared = sharedItems,
                    me = me,
                    now = now,
                    replyingTo = replyingTo,
                    onReplyTo = { replyingTo = it },
                    onReact = { messageId, emoji ->
                        scope.launch {
                            room.react(messageId, emoji)
                                .onFailure { failure -> chatError = failure.message ?: "Couldn't react." }
                        }
                    },
                    onError = { chatError = it },
                    onDismiss = { chatOpen = false; replyingTo = null },
                )
            }
            if (chatError != null) {
                Text(
                    chatError ?: "",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }

            RoomControls(
                unread = unread,
                live = live != null,
                micOn = media.micOn,
                cameraOn = media.cameraOn,
                onMic = {
                    if (media.micOn || granted(Manifest.permission.RECORD_AUDIO)) toggleMic()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onCamera = {
                    if (media.cameraOn || granted(Manifest.permission.CAMERA)) toggleCamera()
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                },
                onChat = { chatOpen = true },
                onHeart = { scope.launch { room.touch("HEART").onFailure { notice = it.message } } },
                onTouch = { touchOpen = true },
            )
        }
    }

    // The back gesture closes the chat before it leaves the room.
    androidx.activity.compose.BackHandler(enabled = chatOpen) { chatOpen = false }

    if (togetherOpen) {
        ModalBottomSheet(
            onDismissRequest = { togetherOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ViroColors.surface,
        ) {
            DoSomethingTogether(
                current = shape?.intent,
                // Music can sit beside cooking or talking; a film or a quiet room has its own.
                musicBeside = when {
                    shape == null || shape.primary == "MUSIC" || shape.primary == "VIDEO" -> null
                    "MUSIC" in shape.secondary -> false
                    else -> true
                },
                onPick = { intent ->
                    togetherOpen = false
                    scope.launch {
                        room.change(MomentRoomChangeBody(op = "TRANSFORM", intent = intent.key))
                            .onFailure { notice = it.message }
                    }
                },
                onTimer = { togetherOpen = false; timerOpen = true },
                onAsk = { togetherOpen = false; askOpen = true },
                onScene = { togetherOpen = false; sceneOpen = true },
                // Only offered when there are two things here worth choosing
                // between: something playing, and people who can be seen.
                swapLabel = when {
                    shape == null -> null
                    focus != null -> "Put the " + (if (shape.primary == "MUSIC") "music" else "film") + " back"
                    shape.primary == "VIDEO" -> "Make the people bigger"
                    shape.primary == "MUSIC" -> "Make the people bigger"
                    else -> null
                },
                onSwap = {
                    togetherOpen = false
                    focus = if (focus == null) "PRESENCE" else null
                },
                onMusic = { add ->
                    togetherOpen = false
                    scope.launch {
                        room.change(MomentRoomChangeBody(op = if (add) "ADD" else "REMOVE", module = "MUSIC"))
                            .onFailure { notice = it.message }
                        if (!add && playerView.kind == "AUDIO") shared?.playback?.stop()
                    }
                },
            )
        }
    }
    if (timerOpen) {
        ModalBottomSheet(onDismissRequest = { timerOpen = false }, containerColor = ViroColors.surface) {
            TimerSheet(onStart = { minutes, label ->
                timerOpen = false
                scope.launch {
                    if (shape != null && "TIMER" !in shape.secondary) {
                        room.change(MomentRoomChangeBody(op = "ADD", module = "TIMER")).onFailure { notice = it.message; return@launch }
                    }
                    room.timer(MomentTimerBody("START", durationMs = minutes * 60_000L, label = label)).onFailure { notice = it.message }
                }
            })
        }
    }
    if (askOpen) {
        ModalBottomSheet(onDismissRequest = { askOpen = false }, containerColor = ViroColors.surface) {
            ChoiceSheet(onAsk = { question, options ->
                askOpen = false
                scope.launch {
                    if (shape != null && "CHOICE" !in shape.secondary) {
                        room.change(MomentRoomChangeBody(op = "ADD", module = "CHOICE")).onFailure { notice = it.message; return@launch }
                    }
                    room.choice(MomentChoiceBody("ASK", question = question, options = options)).onFailure { notice = it.message }
                }
            })
        }
    }
    if (sceneOpen) {
        ModalBottomSheet(onDismissRequest = { sceneOpen = false }, containerColor = ViroColors.surface) {
            SceneSheet(
                current = scene,
                pinned = shape?.scenePinned == true,
                onPick = { picked ->
                    sceneOpen = false
                    scope.launch {
                        room.change(MomentRoomChangeBody(op = "SCENE", scene = picked))
                            .onFailure { notice = it.message }
                    }
                },
            )
        }
    }
    if (touchOpen) {
        ModalBottomSheet(onDismissRequest = { touchOpen = false }, containerColor = ViroColors.surface) {
            TouchSheet(participants, me, onSend = { kind, to ->
                touchOpen = false
                scope.launch { room.touch(kind, to).onFailure { notice = it.message } }
            })
        }
    }
    if (peopleOpen) {
        ModalBottomSheet(onDismissRequest = { peopleOpen = false }, containerColor = ViroColors.surface) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.6f)) {
                RoomPeople(
                    moment = moment,
                    participants = participants,
                    me = me,
                    onMessage = { id, name -> peopleOpen = false; onOpenChat(id, name) },
                    onInvite = { peopleOpen = false; inviteOpen = true },
                    onTalk = { m ->
                        peopleOpen = false
                        scope.launch {
                            // Access is rechecked server-side right before the call.
                            session.moments.verify(m.id).onSuccess { fresh ->
                                onCall(fresh.creatorUserId, null, fresh.displayName)
                            }
                        }
                    },
                )
            }
        }
    }
    val current = moment
    if (inviteOpen && current != null) {
        InviteSheet(
            session = session,
            momentId = current.id,
            onDismiss = { inviteOpen = false },
            onInvited = { scope.launch { room.refresh() } },
        )
    }
}

@Composable
private fun RoomHeader(
    moment: MomentDto?,
    intentLabel: String?,
    encrypted: Boolean,
    now: Long,
    onBack: () -> Unit,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    isHost: Boolean,
    onPeople: () -> Unit,
    onInvite: () -> Unit,
    onTogether: () -> Unit,
    onEnd: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(
                intentLabel ?: moment?.activity() ?: "Moment",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The lock is claimed only once something has actually been sealed
            // here — a promise that appears before it is true is worse than none.
            Text(
                (if (encrypted) "🔒 " else "") +
                    (moment?.let { "${it.displayName} · ${remainingMinutes(it.endsAt(), now)} min left" } ?: "…"),
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { onMenu(true) }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
                DropdownMenuItem(text = { Text("Do something together") }, onClick = onTogether)
                DropdownMenuItem(text = { Text("People here") }, onClick = onPeople)
                if (isHost) DropdownMenuItem(text = { Text("Invite someone") }, onClick = onInvite)
                DropdownMenuItem(
                    text = { Text(if (isHost) "End this Moment" else "Leave", color = ViroColors.textPrimary) },
                    onClick = onEnd,
                )
            }
        }
    }
}

/**
 * Microphone, camera, touch and chat stay near the thumb. Changing what the
 * room is lives in the header menu beside people and leaving.
 * The microphone and camera start off, every time, for everyone.
 */
@Composable
private fun RoomControls(
    unread: Int,
    live: Boolean,
    micOn: Boolean,
    cameraOn: Boolean,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onChat: () -> Unit,
    onHeart: () -> Unit,
    onTouch: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (live) {
                MediaToggle(on = micOn, onIcon = Icons.Default.Mic, offIcon = Icons.Default.MicOff,
                    label = if (micOn) "Turn microphone off" else "Turn microphone on", onClick = onMic)
                MediaToggle(on = cameraOn, onIcon = Icons.Default.Videocam, offIcon = Icons.Default.VideocamOff,
                    label = if (cameraOn) "Turn camera off" else "Turn camera on", onClick = onCamera)
            }
            TouchButton(onHeart = onHeart, onChoose = onTouch)
            BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 9) "9+" else "$unread") } }) {
                RoundAction("Chat", onChat)
            }
        }
    }
}

@Composable
private fun MediaToggle(
    on: Boolean,
    onIcon: ImageVector,
    offIcon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        // Lit when on, so nobody has to wonder whether they can be seen or heard.
        color = if (on) Color.White else Color.White.copy(alpha = 0.12f),
        modifier = Modifier.size(56.dp).clickable(onClickLabel = label, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (on) onIcon else offIcon, label, tint = if (on) Color.Black else Color.White)
        }
    }
}

@Composable
private fun RoundAction(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.1f),
        modifier = Modifier.height(56.dp).width(76.dp).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * What the room could become. Only what this build can actually deliver is
 * listed — nothing here leads to a screen that says "coming soon".
 */
@Composable
private fun DoSomethingTogether(
    current: String?,
    musicBeside: Boolean?,
    onPick: (MomentIntent) -> Unit,
    onMusic: (add: Boolean) -> Unit,
    onTimer: () -> Unit,
    onAsk: () -> Unit,
    onScene: () -> Unit,
    /** Null when this room has nothing worth swapping. */
    swapLabel: String?,
    onSwap: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("Do something together", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "The room changes for everyone here. Nobody has to leave.",
            color = ViroColors.textMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        TogetherAction("Set a timer for everyone", onTimer)
        TogetherAction("Ask everyone something", onAsk)
        TogetherAction("Change how the room looks", onScene)
        if (swapLabel != null) TogetherAction(swapLabel, onSwap)
        if (musicBeside != null) {
            TextButton(onClick = { onMusic(musicBeside) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (musicBeside) "Put some music on" else "Turn the music off",
                    color = ViroColors.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                )
            }
        }
        MomentIntent.offered().filter { it.key != current }.forEach { intent ->
            TextButton(onClick = { onPick(intent) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    intent.label,
                    color = ViroColors.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

/**
 * Choosing how the room looks.
 *
 * Every activity brings its own atmosphere, which is right most of the time
 * and wrong the moment somebody wants their late-night cooking to feel like
 * late night. Picking one holds it through the next transformation, and
 * "Match the activity" hands it back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SceneSheet(current: String, pinned: Boolean, onPick: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("How the room looks", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "For everybody here, not just you.",
            color = ViroColors.textMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for ((key, label) in sceneNames) {
                val chosen = pinned && key == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(72.dp).clickable { onPick(key) },
                ) {
                    Box(
                        Modifier.size(72.dp, 54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                if (chosen) 2.dp else 0.dp,
                                if (chosen) ViroColors.BlueAccent else Color.Transparent,
                                RoundedCornerShape(14.dp),
                            ),
                    ) {
                        SceneSwatch(key, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        label,
                        color = if (chosen) ViroColors.BlueAccent else ViroColors.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (pinned) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { onPick(null) }) {
                Text("Match the activity again", color = ViroColors.BlueAccent)
            }
        }
    }
}

/** Something that happened in the room, whoever did it. */
private sealed interface RoomEntry {
    /** When, on the server's clock, so the two kinds sort against each other. */
    val at: Long

    data class Said(val message: com.viroreach.core.network.MomentMessageDto, override val at: Long) : RoomEntry
    data class Brought(val media: com.viroreach.core.network.MomentMediaDto, override val at: Long) : RoomEntry
}

/**
 * The two kinds of event, in the order they happened.
 *
 * Anything without a time goes last rather than first: a media item from a
 * server that does not send one is almost certainly recent, and burying it at
 * the top of the room would be the wrong guess in the more annoying direction.
 */
private fun roomEntries(
    messages: List<com.viroreach.core.network.MomentMessageDto>,
    shared: List<com.viroreach.core.network.MomentMediaDto>,
): List<RoomEntry> {
    val said = messages.map { RoomEntry.Said(it, instantOf(it.createdAt) ?: 0L) }
    val brought = shared.map { RoomEntry.Brought(it, instantOf(it.createdAt) ?: Long.MAX_VALUE) }
    return (said + brought).sortedBy { it.at }
}

private fun instantOf(raw: String?): Long? =
    raw?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

/**
 * How many people have said something, and the last thing said.
 *
 * The room shows this one line and nothing else. Remarks used to be stacked
 * over the scene, four at a time, fading: that is a heads-up display, not a
 * conversation, and it put words on top of the thing people came to look at.
 * Reading meant squinting past the music bar, and there was no way to see
 * anything older than the last few.
 *
 * So the room keeps a door instead of a window. This is the door.
 */
@Composable
private fun RoomCommentsBar(
    count: Int,
    latest: com.viroreach.core.network.MomentMessageDto?,
    me: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.45f),
        modifier = modifier.clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (count == 0) "Comments" else "Comments · " + count,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (latest != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        (if (latest.senderUserId == me) "You" else latest.senderName.substringBefore(' ')) +
                            ": " + (latest.body ?: com.viroreach.app.moments.SEALED_UNREADABLE),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Say something",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "Open comments",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * The whole conversation, opened.
 *
 * A list rather than bubbles. Bubbles are for a chat between two people, where
 * which side a thing sits on is the only way to tell who said it; a room has
 * several people in it and a name against a face reads faster than a colour.
 * This is the shape every comment section people already know uses, and there
 * is no reason for a Moment to be the exception.
 *
 * What somebody brought stays in the same list, because it happened in the
 * same conversation — the thread is the room's memory, not just its chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomCommentsSheet(
    room: com.viroreach.app.moments.MomentRoomState,
    messages: List<com.viroreach.core.network.MomentMessageDto>,
    shared: List<com.viroreach.core.network.MomentMediaDto>,
    me: String?,
    now: Long,
    replyingTo: com.viroreach.core.network.MomentMessageDto?,
    onReplyTo: (com.viroreach.core.network.MomentMessageDto?) -> Unit,
    onReact: (messageId: String, emoji: String?) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = remember(messages, shared) { roomEntries(messages, shared) }
    val listState = rememberLazyListState()
    // Opening lands on the newest, the way arriving in a conversation should.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }
    var reacting by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ViroColors.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            Text(
                if (messages.isEmpty()) "Comments" else "Comments · " + messages.size,
                color = ViroColors.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            HorizontalDivider(color = ViroColors.textMuted.copy(alpha = 0.15f))
            if (entries.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing said yet.",
                        color = ViroColors.textMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(entries.size, key = { entries[it].key() }) { i ->
                        when (val entry = entries[i]) {
                            is RoomEntry.Said -> CommentRow(
                                message = entry.message,
                                answering = entry.message.replyToId?.let { id -> messages.firstOrNull { it.id == id } },
                                me = me,
                                now = now,
                                open = reacting == entry.message.id,
                                onOpen = {
                                    reacting = if (reacting == entry.message.id) null else entry.message.id
                                },
                                onReact = { emoji ->
                                    reacting = null
                                    onReact(entry.message.id, emoji)
                                },
                                onReply = {
                                    reacting = null
                                    onReplyTo(entry.message)
                                },
                            )
                            is RoomEntry.Brought -> BroughtRow(entry.media, me)
                        }
                    }
                }
            }
            HorizontalDivider(color = ViroColors.textMuted.copy(alpha = 0.15f))
            RoomComposer(
                room = room,
                replyingTo = replyingTo,
                me = me,
                onCancelReply = { onReplyTo(null) },
                onError = onError,
                onDone = {},
            )
        }
    }
}

/** A key that is stable across both kinds, so the list does not re-shuffle. */
private fun RoomEntry.key(): String = when (this) {
    is RoomEntry.Said -> "said:" + message.id
    is RoomEntry.Brought -> "brought:" + media.id
}

/**
 * One comment, the way a comment is usually shown: a face, a name, when, and
 * what was said — then what you can do about it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommentRow(
    message: com.viroreach.core.network.MomentMessageDto,
    answering: com.viroreach.core.network.MomentMessageDto?,
    me: String?,
    now: Long,
    open: Boolean,
    onOpen: () -> Unit,
    onReact: (String?) -> Unit,
    onReply: () -> Unit,
) {
    val mine = message.senderUserId == me
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ViroAvatar(
            size = ViroAvatarSize.Small,
            imageUrl = null,
            displayName = if (mine) "You" else message.senderName,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (mine) "You" else message.senderName.substringBefore(' '),
                    color = ViroColors.textPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    said(message.createdAt, now),
                    color = ViroColors.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (answering != null) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(20.dp)
                            .clip(CircleShape)
                            .background(ViroColors.BlueAccent.copy(alpha = 0.7f)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        (if (answering.senderUserId == me) "You" else answering.senderName.substringBefore(' ')) +
                            ": " + (answering.body ?: com.viroreach.app.moments.SEALED_UNREADABLE),
                        color = ViroColors.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                message.body ?: com.viroreach.app.moments.SEALED_UNREADABLE,
                color = ViroColors.textPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (message.reactions.isNotEmpty() || open) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (reaction in message.reactions.take(4)) {
                        Surface(
                            color = ViroColors.textMuted.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(end = 4.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(reaction.emoji, style = MaterialTheme.typography.labelMedium)
                                // One is the mark itself; a number earns its
                                // place only once somebody has agreed.
                                if (reaction.userIds.size > 1) {
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        reaction.userIds.size.toString(),
                                        color = ViroColors.textMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    if (!open) {
                        Text(
                            "Reply",
                            color = ViroColors.textMuted,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onReply)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            if (open) {
                Spacer(Modifier.height(6.dp))
                ReactionChoices(
                    chosen = message.reactions.firstOrNull { me != null && me in it.userIds }?.emoji,
                    onPick = onReact,
                    onReply = onReply,
                )
            }
        }
    }
}

/** Somebody put something on: an event in the thread, not a remark. */
@Composable
private fun BroughtRow(media: com.viroreach.core.network.MomentMediaDto, me: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (media.kind == "VIDEO") "▶" else "♪",
            color = ViroColors.BlueAccent,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            (if (media.ownerUserId == me) "You" else media.ownerName.substringBefore(' ')) + " brought ",
            color = ViroColors.textMuted,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            media.title,
            color = ViroColors.textPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * How long ago, in as few characters as it takes.
 *
 * A clock time would be wrong here: a room lasts an hour or two, so what
 * matters is whether something was said just now or a while back, not what the
 * wall clock said when it happened.
 */
private fun said(createdAt: String, now: Long): String {
    val at = instantOf(createdAt) ?: return ""
    val seconds = ((now - at) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 45 -> "now"
        seconds < 3600 -> (seconds / 60).coerceAtLeast(1) .toString() + "m"
        seconds < 86_400 -> (seconds / 3600).toString() + "h"
        else -> (seconds / 86_400).toString() + "d"
    }
}


/**
 * The few ways to answer without saying anything.
 *
 * Deliberately few, and deliberately kind. A room is people keeping each other
 * company, so these are the answers you would actually give somebody sitting
 * next to you. Nothing here is a way to be unpleasant to someone who has just
 * said something honest.
 */
@Composable
private fun ReactionChoices(chosen: String?, onPick: (String?) -> Unit, onReply: () -> Unit) {
    Surface(color = ViroColors.textMuted.copy(alpha = 0.14f), shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (emoji in ROOM_REACTIONS) {
                val picked = emoji == chosen
                Text(
                    emoji,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (picked) ViroColors.BlueAccent.copy(alpha = 0.35f) else Color.Transparent)
                        // Pressing the one already given takes it back, which
                        // is the only way out that does not need a second
                        // control explaining itself.
                        .clickable { onPick(if (picked) null else emoji) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            Box(Modifier.width(1.dp).height(18.dp).background(ViroColors.textMuted.copy(alpha = 0.3f)))
            Text(
                "Reply",
                color = ViroColors.BlueAccent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onReply)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private val ROOM_REACTIONS = listOf("❤️", "😂", "🙌", "😮", "🥺")

/** One line to say something, and nothing else taken off the screen for it. */
@Composable
private fun RoomComposer(
    room: com.viroreach.app.moments.MomentRoomState,
    replyingTo: com.viroreach.core.network.MomentMessageDto?,
    me: String?,
    onCancelReply: () -> Unit,
    onError: (String) -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Whatever is being answered at the moment Send is pressed, not whatever
    // it was when this was first composed.
    val answering by rememberUpdatedState(replyingTo)
    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) {
            onDone()
        } else {
            draft = ""
            val to = answering?.id
            scope.launch { room.send(text, to).onFailure { onError(it.message ?: "Couldn't send.") } }
            onCancelReply()
        }
    }
    Column(Modifier.fillMaxWidth()) {
    if (replyingTo != null) {
        // What is about to be answered, so nobody sends a reply into the
        // wrong conversation — and a way out of it that is not "delete what
        // you have written".
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(ViroColors.BlueAccent),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Replying to " + (if (replyingTo.senderUserId == me) "yourself" else replyingTo.senderName.substringBefore(' ')),
                    color = ViroColors.BlueAccent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    replyingTo.body ?: com.viroreach.app.moments.SEALED_UNREADABLE,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Default.Close,
                contentDescription = "Stop replying",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancelReply)
                    .padding(5.dp),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(500) },
            placeholder = { Text("Say something", color = Color.White.copy(alpha = 0.5f)) },
            modifier = Modifier.weight(1f).focusRequester(focus),
            maxLines = 3,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color.Black.copy(alpha = 0.35f),
                unfocusedContainerColor = Color.Black.copy(alpha = 0.35f),
                focusedBorderColor = ViroColors.BlueAccent.copy(alpha = 0.7f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                cursorColor = ViroColors.BlueAccent,
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Send,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { send() }) {
            Text("Send", color = ViroColors.BlueAccent, fontWeight = FontWeight.SemiBold)
        }
    }
    }
}

@Composable
private fun TogetherAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = ViroColors.textPrimary, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
    }
}

@Composable
private fun UnsupportedHere() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Someone here is doing something this version of Viro can't show yet.",
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text("Update Viro to join in.", color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyMedium)
    }
}
