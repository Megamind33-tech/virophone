package com.viroreach.app.moments

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    val promos by session.promotions.promotions.collectAsState()
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
        repo.refresh(); repo.refreshInvitations(); session.promotions.refresh()
        var ticks = 0
        while (true) {
            delay(1000)
            repo.prune()
            clock = repo.now()
            if (++ticks % 30 == 0) { repo.refresh(); repo.refreshInvitations() }
            // Rarely: what the platform is saying changes on the scale of
            // days, and asking every half minute would be asking for nothing.
            if (ticks % 600 == 0) session.promotions.refresh()
        }
    }
    val me = session.tokenStore.getUserId()
    val own = moments.firstOrNull { it.creatorUserId == me }
    val peers = moments.filter { it.creatorUserId != me }

    // Who is holding a door open for this person specifically. That Moment
    // leads, because being asked outranks being available.
    val invitedIds = remember(invitations) { invitations.map { it.moment.id }.toSet() }
    val featured = peers.firstOrNull { it.id in invitedIds } ?: peers.firstOrNull()
    val secondary = peers.filter { it.id != featured?.id && it.id !in invitedIds }
    val pendingInvites = invitations.filter { it.moment.id != featured?.id }

    MomentsTheme {
      ViroScreenBackground {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Text("Now", color = ViroColors.textPrimary, style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (peers.isEmpty() && own == null) "Nobody's around just now" else "Your people are around",
                    color = ViroColors.textMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Your own Moment is a live state, not an invitation. It stays
                // small so it cannot outshout somebody asking for you.
                if (own != null) {
                    item(key = "mine") {
                        YourMomentStrip(
                            own = own,
                            photoUrl = profile.effectivePhotoUrl,
                            displayName = profile.displayName,
                            clock = clock,
                            onReturn = { roomId = own.id },
                            onManage = { manage = own.id },
                        )
                    }
                }

                if (featured != null) {
                    item(key = "featured-" + featured.id) {
                        FeaturedMoment(
                            m = featured,
                            clock = clock,
                            invited = featured.id in invitedIds,
                            busy = busy,
                            onStepIn = { actionError = null; roomId = featured.id },
                            onKnock = {
                                actionError = null
                                busy = true
                                scope.launch { repo.knock(featured.id).onFailure { actionError = it.message }; busy = false }
                            },
                        )
                    }
                }

                // After whoever is asking for you, never above them. One at a
                // time, because two would be a feed of advertisements.
                promos.firstOrNull()?.let { promo ->
                    item(key = "promo-" + promo.id) {
                        PromotionStrip(
                            promo = promo,
                            onDismiss = { scope.launch { session.promotions.dismiss(promo.id) } },
                        )
                    }
                }

                items(secondary, key = { it.id }) { m ->
                    SecondaryMoment(m = m, clock = clock, onOpen = { actionError = null; roomId = m.id })
                }

                if (pendingInvites.isNotEmpty()) {
                    items(pendingInvites, key = { it.invitationId }) { invitation ->
                        InvitationRow(
                            moment = invitation.moment,
                            onOpen = { roomId = invitation.moment.id },
                            onDismiss = { scope.launch { repo.declineInvitation(invitation.invitationId) } },
                        )
                    }
                }

                // Nothing is manufactured to fill the screen. A quiet evening
                // is allowed to look like one.
                if (featured == null && secondary.isEmpty() && pendingInvites.isEmpty()) {
                    item(key = "empty") {
                        if (loaded) {
                            Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Your people are quiet right now.",
                                    color = ViroColors.textPrimary,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Open a Moment for them.",
                                    color = ViroColors.textMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (own == null) {
                                    Spacer(Modifier.height(20.dp))
                                    Button(onClick = { create = true }) { Text("Start a Moment") }
                                }
                            }
                        } else {
                            Text("Checking who's around…", color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // A failed refresh keeps whatever is already on screen: losing
                // the room somebody opened because a request timed out is worse
                // than showing it a minute stale.
                if (error != null) item(key = "error") {
                    TextButton(onClick = { scope.launch { repo.refresh() } }) {
                        Text("Couldn't refresh right now · Try again", color = ViroColors.textMuted)
                    }
                }
                if (actionError != null) item(key = "action-error") {
                    Text(actionError!!, color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall)
                }
                // Room for the button that floats over the end of the list.
                if (own == null) item(key = "fab-room") { Spacer(Modifier.height(72.dp)) }
            }
        }

        // Opening a Moment cannot depend on nobody else having one. This used
        // to live only in the empty state, so the moment anybody else was
        // active it disappeared and there was no way to start your own.
        if (own == null) {
            ExtendedFloatingActionButton(
                onClick = { create = true },
                containerColor = ViroColors.BlueAccent,
                contentColor = ViroColors.NavyBackground,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                Text("Open a Moment", fontWeight = FontWeight.SemiBold)
            }
        }
      }

        if (create) {
            com.viroreach.app.diagnostics.Breadcrumbs.moment("create-sheet")
            val ctx = androidx.compose.ui.platform.LocalContext.current
            DisposableEffect(Unit) {
                com.viroreach.app.diagnostics.CrashReporter.enter(ctx, "starting a Moment")
                onDispose { com.viroreach.app.diagnostics.CrashReporter.left(ctx) }
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
    val people = m.participantCount ?: 0
    val left = remainingMinutes(m.endsAt(), clock)
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = ViroColors.surfaceRaised,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // The picture is the card. A Moment is somebody making room for
            // you, and that should be visible before the words are read.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(158.dp)
                    .clickable(onClick = onPrimary),
            ) {
                com.viroreach.app.moments.engine.MomentActivityArt(
                    m.intent ?: "BE",
                    Modifier.fillMaxSize(),
                )
                // How much of it is left, and who is already in.
                Row(
                    Modifier.align(Alignment.TopEnd).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (people > 1) CardChip("$people here")
                    CardChip(if (left >= 60) "${left / 60} h left" else "$left min left")
                }
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MomentAvatar(m)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = androidx.compose.ui.graphics.Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            m.activity(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = ViroColors.BlueAccent,
                        modifier = Modifier.clickable(onClick = onPrimary),
                    ) {
                        Text(
                            primaryLabel,
                            color = ViroColors.NavyBackground,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            MomentReactionRow(m, onReact)
        }
    }
}

/** A small fact about a Moment, legible over any of the artwork. */
@Composable
private fun CardChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.38f),
    ) {
        Text(
            text,
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
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
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (emoji in momentReactions) {
            val count = m.reactions.firstOrNull { it.emoji == emoji }?.count ?: 0
            val mine = m.myReaction == emoji
            Surface(
                shape = RoundedCornerShape(50),
                // The strip sits on the raised card now, so an unchosen
                // reaction has to be lighter than it rather than the same.
                color = if (mine) {
                    ViroColors.BlueAccent.copy(alpha = 0.22f)
                } else {
                    androidx.compose.ui.graphics.Color.White.copy(alpha = 0.07f)
                },
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
    val closed by room.closed.collectAsState()
    val scope = rememberCoroutineScope()
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val me = session.tokenStore.getUserId()
    // Faces and voices. The engine joins the room's media once the room is
    // entered and publishes nothing until its person turns something on.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val engine = remember(room) { com.viroreach.voice.webrtc.MomentPresenceEngine(appContext) }
    val live = remember(room) { session.openMomentLive(room.momentId, engine) }
    val video = remember(engine) { com.viroreach.app.moments.engine.MomentVideo(engine) }
    // The room's one shared player. It plays only what the room says, only
    // while this screen is showing.
    var shared by remember { mutableStateOf<com.viroreach.app.moments.engine.SharedPlayback?>(null) }
    val exoPlayer = remember(room) {
        com.viroreach.app.moments.engine.ExoLocalPlayer(appContext) { shared?.playerFailed() }
    }
    val playback = remember(room) {
        com.viroreach.app.moments.engine.SharedPlayback(room, exoPlayer, session::apiUrl).also { shared = it }
    }
    // While this room is open, its player is the one the phone shows controls
    // for. Attaching starts the foreground service; without it Android stops
    // the audio as soon as Viro leaves the screen.
    DisposableEffect(room) {
        com.viroreach.app.moments.engine.MomentPlayerHolder.attach(exoPlayer.exo)
        onDispose { com.viroreach.app.moments.engine.MomentPlayerHolder.detach(appContext) }
    }
    val sharing = remember(room) { session.openMomentMedia(appContext, room, playback, scope) }
    LaunchedEffect(room) {
        room.playback.collect {
            playback.follow(it)
            // Controls in the shade exist while the room is playing something
            // and not otherwise, which is also the only time Android permits
            // the service that keeps the sound alive off screen.
            com.viroreach.app.moments.engine.MomentPlayerHolder
                .playing(appContext, it?.status == "PLAYING")
        }
    }
    // Remembered while the room is open, for the ending: who was here, and for how long.
    var lastPeople by remember { mutableStateOf(participants) }
    LaunchedEffect(participants) { if (participants.isNotEmpty()) lastPeople = participants }
    DisposableEffect(room) {
        com.viroreach.app.diagnostics.CrashReporter.enter(appContext, "a Moment room")
        onDispose { com.viroreach.app.diagnostics.CrashReporter.left(appContext) }
    }
    LaunchedEffect(room) {
        com.viroreach.app.diagnostics.Breadcrumbs.moment("room-enter")
        if (room.enter().isSuccess) live.start()
        com.viroreach.app.diagnostics.Breadcrumbs.moment("room-entered")
        var tick = 0
        while (true) {
            delay(1000)
            clock = System.currentTimeMillis()
            if (!closed) { live.tick(); playback.tick() }
            // Every change arrives as a frame; this is only a safety net for a
            // frame that never came, so it does not need to spend data every second.
            if (!closed && ++tick % 15 == 0) room.refresh()
        }
    }
    // The room's own lifecycle frames arrive through the shared socket flow.
    LaunchedEffect(room) {
        session.moments.frames.collect { (type, payload) -> room.onFrame(type, payload) }
    }
    // However the room goes away — ended, left, taken out of it — the camera
    // and microphone go with it.
    LaunchedEffect(closed) { if (closed) { live.stop(); playback.release() } }
    // Nothing keeps capturing once Viro is no longer on screen.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, live) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            // The camera stops when Viro is off screen — nobody expects to keep
            // being filmed by an app they have put down. What the room is
            // playing does not: the evening carries on for everyone else, and
            // silencing it only for the person who glanced at a message is the
            // thing that made the room feel like an app rather than a place.
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                scope.launch { live.onBackground() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(room) {
        onDispose {
            // The screen's own scope ends with it; releasing the camera and
            // microphone must not depend on that, so it runs on its own.
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) { live.stop() }
            playback.release()
            exoPlayer.release()
            scope.launch { room.leave() }
        }
    }

    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = ViroColors.background) {
            if (closed) {
                MomentEnding(
                    repo = session.moments,
                    momentId = room.momentId,
                    people = lastPeople,
                    me = me,
                    now = clock,
                    onDone = onBack,
                )
            } else {
                com.viroreach.app.moments.engine.MomentRoomEngine(
                    session = session,
                    room = room,
                    now = clock,
                    onBack = onBack,
                    onEnd = {
                        val current = moment
                        scope.launch {
                            if (current != null && current.creatorUserId == me) {
                                session.moments.end(current.id)
                                room.markClosed()
                            } else {
                                room.leave()
                                onBack()
                            }
                        }
                    },
                    onCall = onCall,
                    onOpenChat = onOpenChat,
                    live = live,
                    video = video,
                    shared = sharing,
                    exo = exoPlayer.exo,
                )
            }
        }
    }
}

/**
 * How a Moment ends: in human words, with the time people had.
 *
 * Not "disconnected", not "expired" — the Moment is over, and what is worth
 * saying is who it was with and for how long.
 */
@Composable
private fun MomentEnding(
    repo: MomentsRepository,
    momentId: String,
    people: List<com.viroreach.core.network.MomentParticipantDto>,
    me: String?,
    now: Long,
    onDone: () -> Unit,
) {
    val others = people.filter { it.userId != me }
    val mine = people.firstOrNull { it.userId == me }?.joinedAt
    val since = listOfNotNull(mine, others.firstOrNull()?.joinedAt)
        .mapNotNull { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        .maxOrNull()
    val localMinutes = since?.let { ((now - it).coerceAtLeast(0) / 60_000).toInt() }

    // The ending is drawn from what this phone already knows and then corrects
    // itself when the server answers. Nobody should watch a spinner to be told
    // their evening is over.
    var ending by remember(momentId) { mutableStateOf<com.viroreach.core.network.MomentEndingDto?>(null) }
    var answered by remember(momentId) { mutableStateOf(false) }
    var busy by remember(momentId) { mutableStateOf(false) }
    // Nothing is selected to begin with: keeping is a thing somebody chooses.
    val chosen = remember(momentId) { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(momentId) { ending = repo.ending(momentId) }

    val who = ending?.withPeople?.takeIf { it.isNotEmpty() }
        ?: others.map { it.displayName.substringBefore(' ') }
    val minutes = ending?.togetherMs?.let { (it / 60_000).toInt() } ?: localMinutes
    val offers = ending?.offers.orEmpty().filter { !it.kept }

    Box(Modifier.fillMaxSize()) {
        com.viroreach.app.moments.engine.MomentScene("QUIET")
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "That Moment is over.",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.headlineSmall,
            )
            if (who.isNotEmpty() && minutes != null && minutes > 0) {
                Spacer(Modifier.height(12.dp))
                val name = if (who.size == 1) who.first() else "${who.size} people"
                val howLong = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
                Text(
                    "You and $name spent $howLong together.",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            // Asked once, and only when there is something to ask about. An
            // ending with nothing worth keeping just says goodbye.
            if (!answered && offers.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text(
                    "Keep anything from this?",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))
                for (offer in offers) {
                    KeepsakeChoice(
                        offer = offer,
                        selected = offer.id in chosen,
                        onToggle = { if (offer.id in chosen) chosen.remove(offer.id) else chosen.add(offer.id) },
                    )
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    enabled = !busy && chosen.isNotEmpty(),
                    onClick = {
                        busy = true
                        scope.launch {
                            repo.keep(momentId, chosen.toList())
                            busy = false
                            answered = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (chosen.size > 1) "Keep these" else "Keep this") }
                TextButton(onClick = { answered = true }, enabled = !busy) {
                    Text("Keep nothing", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f))
                }
            } else if (answered && chosen.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Kept.",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(Modifier.height(28.dp))
            TextButton(onClick = onDone) {
                Text("Back to Now", color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

/**
 * One thing that could be kept.
 *
 * Deliberately unticked until touched, and plainly worded: this is the last
 * screen of an evening, not a form.
 */
@Composable
private fun KeepsakeChoice(
    offer: com.viroreach.core.network.MomentKeepsakeOfferDto,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) ViroColors.BlueAccent.copy(alpha = 0.22f)
        else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onToggle),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    offer.title,
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val note = offer.detail ?: when (offer.kind) {
                    "MOMENT" -> "The time itself"
                    else -> null
                }
                if (note != null) {
                    Text(
                        note,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                if (selected) "✓" else "",
                color = ViroColors.BlueAccent,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
/*
 * RoomChat and MessageBubble lived here: a full scrolling transcript with
 * long-press reactions. The room shows the last few remarks over the scene
 * instead, so nothing reached them any more. Message reactions still exist
 * server-side and in the realtime frames; there is simply no control for them
 * while chat reads as comments.
 */


@Composable
internal fun RoomPeople(
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
internal fun InviteSheet(session: SessionManager, momentId: String, onDismiss: () -> Unit, onInvited: () -> Unit) {
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

/** What the host said, or a plain line when they said nothing. */
private fun MomentDto.invitation(): String = invitationText?.takeIf { it.isNotBlank() } ?: "Come join me"

/** "Cooking dinner · 28 min left", rather than a timer pill off on its own. */
private fun MomentDto.line(clock: Long): String {
    val left = remainingMinutes(endsAt(), clock)
    return activity() + " · " + (if (left >= 60) "another " + (left / 60) + " h" else "another " + left + " min")
}

/**
 * The person's own Moment: a live state, kept deliberately small.
 *
 * It used to be the largest thing on the screen, which put what you are
 * already doing above everyone asking for you. This says the same facts in a
 * strip and gets out of the way.
 */
@Composable
private fun YourMomentStrip(
    own: MomentDto,
    photoUrl: String?,
    displayName: String,
    clock: Long,
    onReturn: () -> Unit,
    onManage: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = ViroColors.surfaceRaised,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clickable(onClick = onManage)) {
                ViroAvatar(size = ViroAvatarSize.Small, imageUrl = photoUrl, displayName = displayName)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onManage)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot()
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "YOUR MOMENT",
                        color = ViroColors.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    own.activity(),
                    color = ViroColors.textPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    remainingMinutes(own.endsAt(), clock).toString() + " min left",
                    color = ViroColors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onReturn) {
                Text("Return", color = ViroColors.BlueAccent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The strongest invitation on the screen, as a doorway rather than a post.
 *
 * What the person said comes first and largest, because "come keep me company"
 * is the reason to walk in and "Cooking" is only the label on the door. Their
 * face sits with their name rather than in a ring in the corner, and the one
 * thing to do is Step in.
 */
@Composable
private fun FeaturedMoment(
    m: MomentDto,
    clock: Long,
    invited: Boolean,
    busy: Boolean,
    onStepIn: () -> Unit,
    onKnock: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = ViroColors.surfaceRaised,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().height(320.dp).clickable(onClick = onStepIn)) {
            com.viroreach.app.moments.engine.MomentActivityArt(m.intent ?: "BE", Modifier.fillMaxSize())
            // Only behind the words, so the scene keeps its own light.
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.72f)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to androidx.compose.ui.graphics.Color.Transparent,
                            0.55f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                            1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
            )
            MomentPresenceEdge(
                intent = m.intent ?: "BE",
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            )
            if (invited) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
                ) {
                    Text(
                        m.displayName.substringBefore(' ') + " asked for you",
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MomentAvatar(m)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        m.displayName,
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                // The loudest thing on the card: what they actually said.
                Text(
                    m.invitation(),
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot()
                    Spacer(Modifier.width(6.dp))
                    Text(
                        m.line(clock),
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                com.viroreach.app.moments.engine.MomentMood.of(m.mood)?.let { mood ->
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            m.displayName.substringBefore(' ') + " is ",
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        com.viroreach.app.moments.engine.MoodTag(
                            mood,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.88f),
                        )
                    }
                }
                WhoIsHere(m, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onStepIn,
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 46.dp),
                    ) {
                        Text("Step in", fontWeight = FontWeight.SemiBold)
                    }
                    // Only where the room actually takes a knock.
                    if (m.allowVoice) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onKnock, enabled = !busy, modifier = Modifier.heightIn(min = 46.dp)) {
                            Text("Knock", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Something the platform is saying, in the feed without pretending to be a
 * person.
 *
 * Deliberately the quietest thing on the screen: flat surface rather than a
 * raised card, no picture, no button, no colour of its own beyond one small
 * mark. A Moment is somebody holding a door open and this is not — so it sits
 * below the person asking for you, it never appears more than one at a time,
 * and the cross is always there.
 *
 * Dismissing is permanent and reaches every device. That is the whole bargain:
 * something shown in a feed about people has to be possible to end for good,
 * or it is an advertisement that keeps coming back.
 */
@Composable
private fun PromotionStrip(
    promo: com.viroreach.core.network.PromotionDto,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ViroColors.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(6.dp).clip(CircleShape)
                    .background(ViroColors.BlueAccent.copy(alpha = 0.7f)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    promo.title,
                    color = ViroColors.textPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                promo.body?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = ViroColors.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.Close,
                    "Dismiss this for good",
                    tint = ViroColors.textMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** A smaller doorway: enough to recognise the person and what they said. */
@Composable
private fun SecondaryMoment(m: MomentDto, clock: Long, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ViroColors.surfaceRaised,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(108.dp).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(108.dp).fillMaxHeight()) {
                com.viroreach.app.moments.engine.MomentActivityArt(m.intent ?: "BE", Modifier.fillMaxSize())
                MomentPresenceEdge(
                    intent = m.intent ?: "BE",
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                )
                Box(Modifier.align(Alignment.Center)) { MomentAvatar(m) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    m.displayName,
                    color = ViroColors.textPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    m.invitation(),
                    color = ViroColors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    m.line(clock),
                    color = ViroColors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                com.viroreach.app.moments.engine.MomentMood.of(m.mood)?.let { mood ->
                    Spacer(Modifier.height(3.dp))
                    com.viroreach.app.moments.engine.MoodTag(mood)
                }
                WhoIsHere(m, ViroColors.textMuted)
            }
        }
    }
}

/** An invitation still waiting on an answer, with a way to say no. */
@Composable
private fun InvitationRow(moment: MomentDto, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = ViroColors.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(78.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(78.dp).fillMaxHeight().clickable(onClick = onOpen)) {
                com.viroreach.app.moments.engine.MomentActivityArt(moment.intent ?: "BE", Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.Center)) { MomentAvatar(moment) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
                Text(
                    moment.displayName.substringBefore(' ') + " asked for you",
                    color = ViroColors.textMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    moment.invitation(),
                    color = ViroColors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onOpen) { Text("Step in", color = ViroColors.BlueAccent) }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, "Dismiss invitation", tint = ViroColors.textMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Who is already inside, as people rather than a number.
 *
 * Faces where the server sent them — it only sends a face when that person's
 * own photo setting allows this viewer to see it — and a name beside them. A
 * room with more people than the card can show says how many more, because at
 * that point the number is the useful part.
 */
@Composable
private fun WhoIsHere(m: MomentDto, color: androidx.compose.ui.graphics.Color) {
    val here = m.here
    val total = (m.participantCount ?: 0) - 1
    if (total > 0) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Overlapped slightly, the way people stand together.
            here.forEachIndexed { index, person ->
                Box(Modifier.padding(start = if (index == 0) 0.dp else 0.dp)) {
                    ViroAvatar(
                        size = ViroAvatarSize.Small,
                        imageUrl = person.avatarUrl,
                        displayName = person.displayName,
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            val names = here.map { it.displayName.substringBefore(' ') }
            val unseen = total - here.size
            val said = when {
                names.isEmpty() -> total.toString() + (if (total == 1) " person is here" else " people are here")
                unseen > 0 -> names.joinToString(", ") + " + " + unseen + " are here"
                names.size == 1 -> names[0] + " is here"
                else -> names.dropLast(1).joinToString(", ") + " and " + names.last() + " are here"
            }
            Text(
                said,
                color = color,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Live, said with a shape as well as a colour. */
@Composable
private fun LiveDot() {
    Box(
        Modifier.size(7.dp).clip(CircleShape)
            .background(ViroColors.GreenAvailable),
    )
}

/**
 * The one mark that says a Moment is alive.
 *
 * A hairline along the top of the card that breathes at the pace of whatever
 * is happening — slow for a quiet room, a little quicker where there is music.
 * Deliberately not a ring around an avatar: that is somebody else's product,
 * and it turns a person into a story to be watched.
 */
@Composable
private fun MomentPresenceEdge(intent: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val still = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
    val period = when (intent) {
        "LISTEN" -> 1600
        "CELEBRATE" -> 1800
        "COOK" -> 2600
        "WALK" -> 3000
        "WATCH" -> 4200
        "STAY" -> 5200
        else -> 3400
    }
    val breath = rememberInfiniteTransition(label = "presence")
    val raw by breath.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            androidx.compose.animation.core.tween(period, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "presenceBreath",
    )
    val alpha = if (still) 0.6f else raw
    Box(
        modifier.height(2.dp).background(
            androidx.compose.ui.graphics.Brush.horizontalGradient(
                0f to androidx.compose.ui.graphics.Color.Transparent,
                0.5f to ViroColors.BlueAccent.copy(alpha = alpha),
                1f to androidx.compose.ui.graphics.Color.Transparent,
            ),
        ),
    )
}

/**
 * One thing people can do together, as a picture rather than a row.
 *
 * The art is the room's own atmosphere, so choosing here already shows where
 * you are about to be: the cinema tile is dark, the kitchen tile is warm, and
 * walking into the room feels like the same place.
 */
@Composable
private fun ActivityTile(
    intentKey: String,
    label: String,
    chosen: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val border by animateDpAsState(if (chosen) 2.dp else 0.dp, label = "tileBorder")
    val lift by animateFloatAsState(if (chosen) 1f else 0f, label = "tileLift")
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ViroColors.surfaceRaised,
        modifier = modifier
            .height(112.dp)
            .border(border, ViroColors.BlueAccent.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize()) {
            com.viroreach.app.moments.engine.MomentActivityArt(intentKey, Modifier.fillMaxSize())
            if (lift > 0f) {
                Box(Modifier.fillMaxSize().background(ViroColors.BlueAccent.copy(alpha = 0.16f * lift)))
            }
            Text(
                label,
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateMomentSheet(onDismiss: () -> Unit, onStart: (CreateMomentBody) -> Unit, busy: Boolean, error: String?) {
    // What people want to do together, in their words. Only what this build
    // can actually deliver is listed; the list grows as the room learns more.
    val intents = com.viroreach.app.moments.engine.MomentIntent.offered()
    var intentKey by rememberSaveable { mutableStateOf(intents.firstOrNull()?.key ?: "BE") }
    var somethingElse by rememberSaveable { mutableStateOf(false) }
    var duration by rememberSaveable { mutableIntStateOf(30) }
    var audience by rememberSaveable { mutableStateOf("CONNECTIONS") }
    var text by rememberSaveable { mutableStateOf("") }
    var invitation by rememberSaveable { mutableStateOf("") }
    var moodKey by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = ViroColors.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("What would you like to do together?", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
                Text("A room opens around it. You can change it once you're in.", color = ViroColors.textMuted)
            }
            // Two to a row, each showing the room it opens. A column of
            // identical rectangles told you nothing about the difference
            // between cooking with someone and sitting quietly with them.
            items(intents.chunked(2), key = { it.first().key }) { pair ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (intent in pair) {
                        ActivityTile(
                            intentKey = intent.key,
                            label = intent.label,
                            chosen = !somethingElse && intentKey == intent.key,
                            modifier = Modifier.weight(1f),
                        ) { intentKey = intent.key; somethingElse = false }
                    }
                    // An odd one out keeps its half rather than stretching
                    // across the row and looking like a different kind of thing.
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                ActivityTile(
                    intentKey = "OTHER",
                    label = "Something else",
                    chosen = somethingElse,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { somethingElse = true },
                )
                if (somethingElse) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it.take(60) },
                        label = { Text("What would you like to do?") },
                        supportingText = { Text("${text.length}/60") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                }
            }
            item {
                // What they say is the reason anyone walks in, so it is asked
                // for here rather than buried. Optional: somebody who says
                // nothing gets a plain line, not a sentence invented for them.
                // How they are, before what they will say. Optional, and the
                // artwork answers back as it is chosen.
                Text("How are you today?", color = ViroColors.textPrimary)
                Spacer(Modifier.height(8.dp))
                com.viroreach.app.moments.engine.MoodPicker(
                    selected = com.viroreach.app.moments.engine.MomentMood.of(moodKey),
                    onPick = { moodKey = it?.key },
                )
                Spacer(Modifier.height(20.dp))
                Text("Say something to bring them in", color = ViroColors.textPrimary)
                OutlinedTextField(
                    value = invitation,
                    onValueChange = { invitation = it.take(80) },
                    placeholder = { Text("Come keep me company", color = ViroColors.textMuted) },
                    supportingText = { Text(invitation.length.toString() + "/80 · optional") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                )
            }
            item {
                Text("For how long", color = ViroColors.textPrimary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(15, 30, 60, 120), key = { it }) { minutes ->
                        FilterChip(selected = duration == minutes, onClick = { duration = minutes },
                            label = { Text(if (minutes >= 60) "${minutes / 60} h" else "$minutes min") })
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
                Button(
                    onClick = {
                        val intent = com.viroreach.app.moments.engine.MomentIntent.of(intentKey)
                        onStart(
                            if (somethingElse) {
                                // Their own words, in a room shaped for being together.
                                CreateMomentBody(
                                    "CUSTOM", text.trim(), audience, duration, intent = "BE",
                                    invitationText = invitation.trim().ifBlank { null },
                                    mood = moodKey,
                                )
                            } else {
                                CreateMomentBody(
                                    intent?.legacyType ?: "FREE", null, audience, duration, intent = intentKey,
                                    invitationText = invitation.trim().ifBlank { null },
                                    mood = moodKey,
                                )
                            },
                        )
                    },
                    enabled = !busy && (!somethingElse || text.isNotBlank()),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "Opening…" else "Open the room")
                }
            }
        }
    }
}
