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
import androidx.compose.foundation.pager.rememberPagerState
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

/** Controls follow the selected appearance; artwork owns its own contrast. */
@Composable
fun MomentsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = ViroColors.accent,
        onPrimary = ViroColors.onAccent,
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    // Deliberately not rememberSaveable. It was, and that is a bug with a
    // clear shape: leaving Now saved "the sheet is open" and coming back
    // restored it, so the Moment sheet reopened itself with nobody touching
    // it — a second sheet lifecycle that no tap asked for, on top of whatever
    // the first one had already built. Leaving Now closes the sheet.
    var create by remember { mutableStateOf(false) }
    // Starting a Moment, guarded. A second tap while the sheet is already
    // coming up must not open a second one, and nothing may be built while a
    // previous room is still being released — that overlap is what took the
    // process down.
    val gatePhase by com.viroreach.app.moments.engine.MomentSessionGate.phase.collectAsState()
    var sheetInstance by remember { mutableIntStateOf(0) }
    // A Moment that has just been made and not yet been stepped into.
    var tellAbout by remember { mutableStateOf<MomentDto?>(null) }
    val startMoment: () -> Unit = {
        if (!create && com.viroreach.app.moments.engine.MomentSessionGate.idle()) {
            sheetInstance += 1
            com.viroreach.app.diagnostics.Breadcrumbs.moment(
                "create-sheet-requested instance=$sheetInstance route=Now state=CLOSED",
            )
            create = true
        } else {
            // Rejections are recorded as loudly as acceptances. A tap that
            // does nothing is exactly the thing that is impossible to explain
            // afterwards from a trail that only mentions what worked.
            com.viroreach.app.diagnostics.Breadcrumbs.moment(
                "create-sheet-rejected reason=" +
                    (if (create) "already-open" else "releasing") + " phase=$gatePhase",
            )
        }
    }
    var manage by rememberSaveable { mutableStateOf<String?>(null) }
    var roomId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val knocked by repo.knocked.collectAsState()
    // Which Moment a knock is on its way to. One at a time, because a second
    // tap must not send a second knock.
    var knocking by remember { mutableStateOf<String?>(null) }
    // A knock that did not go through, said once in plain words and then let go.
    var knockNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(knockNotice) { if (knockNotice != null) { delay(4000); knockNotice = null } }
    LaunchedEffect(repo) {
        // Coming back to the tab reuses what is already here; only a list that
        // has actually gone stale is fetched again on the way in.
        if (repo.isStale(30_000)) {
            repo.refresh(); repo.refreshInvitations(); session.promotions.refresh()
        }
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

    // The deck: everyone else's Moments, plus any Moment this person was asked
    // into that the list has not caught up with. Whoever asked for this person
    // specifically leads, because being asked outranks being available.
    val deck = remember(moments, invitations, me) {
        val invitedIds = invitations.map { it.moment.id }.toSet()
        val peers = moments.filter { it.creatorUserId != me }
        val waiting = invitations.filter { invite -> peers.none { it.id == invite.moment.id } && invite.moment.creatorUserId != me }
        (peers.map { NowEntry(it, invited = it.id in invitedIds) } +
            waiting.map { NowEntry(it.moment, invited = true, invitationId = it.invitationId) })
            .sortedByDescending { it.invited }
    }
    val pager = rememberPagerState(
        initialPage = deck.indexOfFirst { it.moment.id == repo.focusedMomentId }.coerceAtLeast(0),
        pageCount = { deck.size },
    )
    // Remember where somebody was, so a trip to a chat and back lands on the
    // same Moment rather than the first one.
    LaunchedEffect(pager, deck) {
        snapshotFlow { pager.settledPage }.collect { page ->
            deck.getOrNull(page)?.let { repo.focusedMomentId = it.moment.id }
        }
    }
    // When the list changes underneath (someone new, someone gone), stay with
    // the Moment that was in front rather than whatever now has its index.
    val deckIds = remember(deck) { deck.map { it.moment.id } }
    LaunchedEffect(deckIds) {
        val index = deckIds.indexOf(repo.focusedMomentId)
        if (index >= 0 && index != pager.currentPage && !pager.isScrollInProgress) pager.scrollToPage(index)
    }
    // With a single Moment the pager is not laid out and its page can be left
    // over from a longer list, so it is clamped rather than trusted.
    val active = if (deck.isEmpty()) null else deck[pager.currentPage.coerceIn(0, deck.lastIndex)].moment
    val clockNow: () -> Long = { clock }

    MomentsTheme {
      ViroScreenBackground {
        NowBackdrop(intentKey = active?.intent, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            NowHeader()
            // As tall as the deck needs and no taller, under the header, with
            // the composer straight after it — never centred in empty space.
            Box(Modifier.weight(1f, fill = false).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                when {
                    deck.isNotEmpty() -> NowMomentDeck(
                        entries = deck,
                        pager = pager,
                        clock = clockNow,
                        knockState = { id ->
                            when {
                                id in knocked -> KnockState.SENT
                                id == knocking -> KnockState.SENDING
                                else -> KnockState.AVAILABLE
                            }
                        },
                        onBeWith = { entry -> actionError = null; roomId = entry.moment.id },
                        onKnock = { entry ->
                            val id = entry.moment.id
                            if (knocking == null && id !in knocked) {
                                knocking = id
                                scope.launch {
                                    repo.knock(id).onFailure { knockNotice = "Couldn't knock right now." }
                                    knocking = null
                                }
                            }
                        },
                        onDecline = { entry ->
                            entry.invitationId?.let { invitation -> scope.launch { repo.declineInvitation(invitation) } }
                        },
                    )
                    // Nothing cached and the first fetch failed: say so, keep the shell.
                    error != null && !loaded -> NowLoadError(onRetry = {
                        scope.launch { repo.refresh(); repo.refreshInvitations() }
                    })
                    !loaded -> NowCardSkeleton()
                    // Nothing is manufactured to fill the screen. A quiet
                    // evening is allowed to look like one.
                    else -> NowEmptyState(showCompose = own == null, onOpenMoment = { startMoment() })
                }
            }
            NowDock {
                when {
                    knockNotice != null -> NowNotice(knockNotice!!, action = null, onAction = {})
                    // A failed refresh keeps whatever is already on screen:
                    // losing the room somebody opened because a request timed
                    // out is worse than showing it a minute stale.
                    error != null && loaded -> NowNotice(
                        "Couldn't refresh Moments.",
                        action = "Try again",
                        onAction = { scope.launch { repo.refresh(); repo.refreshInvitations() } },
                    )
                }
                // After whoever is asking for you, never above them. One at a
                // time, because two would be a feed of advertisements.
                promos.firstOrNull()?.let { promo ->
                    PromotionStrip(
                        promo = promo,
                        onDismiss = { scope.launch { session.promotions.dismiss(promo.id) } },
                    )
                }
                // Your own Moment is a live state, not an invitation, so it
                // lives in the dock rather than competing with the deck. With
                // none running, the dock is where you open one — except when
                // the empty state is already offering exactly that.
                if (own != null) {
                    YourMomentStrip(
                        own = own,
                        photoUrl = profile.effectivePhotoUrl,
                        displayName = profile.displayName,
                        clock = clockNow,
                        onReturn = { roomId = own.id },
                        onManage = { manage = own.id },
                    )
                } else if (deck.isNotEmpty() || !loaded) {
                    NowComposer(onOpenMoment = { startMoment() })
                }
            }
        }
      }

        if (create) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            // Inside the effect, not the composable body. In the body it ran
            // on every recomposition, which is why one tap left three
            // "create-sheet" crumbs in the report and made it look as though
            // the screen was being opened three times. It was not; the trail
            // was lying about the thing it existed to explain.
            DisposableEffect(Unit) {
                com.viroreach.app.diagnostics.Breadcrumbs.moment(
                    "create-sheet-visible instance=$sheetInstance state=VISIBLE",
                )
                com.viroreach.app.diagnostics.CrashReporter.enter(ctx, "starting a Moment")
                onDispose {
                    com.viroreach.app.diagnostics.Breadcrumbs.moment(
                        "create-sheet-disposed instance=$sheetInstance",
                    )
                    com.viroreach.app.diagnostics.CrashReporter.left(ctx)
                }
            }
        }
        if (create) CreateMomentSheet(onDismiss = { create = false }, onStart = { body ->
            busy = true
            scope.launch {
                repo.create(body)
                    .onSuccess { created ->
                        create = false
                        // Who to tell is asked here, between making the Moment
                        // and stepping into it: it is about this Moment, and
                        // asking afterwards would make it an afterthought.
                        tellAbout = created
                    }
                    .onFailure { actionError = it.message }
                busy = false
            }
        }, busy = busy, error = actionError)

        tellAbout?.let { made ->
            TellPeopleSheet(
                session = session,
                moment = made,
                onDone = {
                    tellAbout = null
                    roomId = made.id
                },
            )
        }

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
            room = remember(session, id) { session.openMomentRoom(id) },
            onBack = { roomId = null },
            onCall = onCall,
            onOpenChat = onOpenChat,
        )
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
        // Detaching belongs to the ordered teardown below, not here: it has to
        // happen before the player is released, and two independent effects
        // disposing in whatever order Compose chooses cannot promise that.
        onDispose {}
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
        // Nothing is built until the previous room has genuinely finished
        // being taken apart.
        com.viroreach.app.moments.engine.MomentSessionGate.awaitReleased()
        com.viroreach.app.moments.engine.MomentSessionGate.entering(room.momentId)
        com.viroreach.app.diagnostics.Breadcrumbs.moment("room-enter")
        com.viroreach.app.diagnostics.Breadcrumbs.moment("room-connect-start")
        if (room.enter().isSuccess) live.start()
        com.viroreach.app.moments.engine.MomentSessionGate.active()
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
            // Leaving is handed to the gate rather than to GlobalScope, and in
            // a fixed order. It used to be fire-and-forget, which meant the
            // next room could begin building its own PeerConnectionFactory,
            // EglBase and microphone while these were still being dismantled
            // on native threads. Two of those overlapping does not fail in
            // Kotlin — the process disappears underneath it.
            //
            // The screen's own scope dies with the screen, so none of this may
            // depend on it.
            com.viroreach.app.moments.engine.MomentSessionGate.leaving {
                // The shade's controls stop pointing at this player before it
                // is released; a MediaSession left holding a released player
                // is a native crash of its own.
                com.viroreach.app.moments.engine.MomentPlayerHolder.detach(appContext)
                com.viroreach.app.diagnostics.Breadcrumbs.moment("mic-stop-start")
                live.stop()
                com.viroreach.app.diagnostics.Breadcrumbs.moment("mic-stopped")
                com.viroreach.app.diagnostics.Breadcrumbs.moment("room-disconnect-start")
                runCatching { playback.release() }
                runCatching { exoPlayer.release() }
                com.viroreach.app.diagnostics.Breadcrumbs.moment("room-disconnected")
                com.viroreach.app.diagnostics.Breadcrumbs.moment("room-release-start")
                runCatching { room.leave() }
            }
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
                                // Closed here only if the server agreed to
                                // close it. Marking it shut regardless showed
                                // the host an ending that had not happened,
                                // with everybody else still in a Moment the
                                // host believed was over — worse than an error.
                                session.moments.end(current.id)
                                    .onSuccess { room.markClosed() }
                                    .onFailure { failure ->
                                        room.error.value =
                                            failure.message ?: "Couldn't end this Moment. Try again."
                                    }
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
fun MomentKnockListener(session: SessionManager, onCall: (String?, String?, String) -> Unit, onMessage: (String, String) -> Unit) {
    val repo = session.moments
    var knock by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var responseError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repo) {
        repo.frames.collect { (type, payload) ->
            if (type == "moment.knock") { knock = payload; responseError = null }
            if (type == "moment.knock-answered" && payload["momentId"] == knock?.get("momentId") &&
                payload["knockerUserId"] == knock?.get("knockerUserId")) knock = null
        }
    }
    LaunchedEffect(repo) {
        while (true) {
            if (knock == null && !busy) repo.pendingKnock()?.let { knock = it; responseError = null }
            delay(30_000)
        }
    }
    knock?.let { payload ->
        val knockerId = payload["knockerUserId"] as? String
        val knockerName = payload["knockerName"] as? String ?: "Someone"
        val momentId = payload["momentId"] as? String
        val told = payload["told"] as? String
        AlertDialog(onDismissRequest = { if (!busy) knock = null }, containerColor = ViroColors.surface,
            title = { Text(told ?: "$knockerName wants to talk", color = ViroColors.textPrimary) },
            text = { Column {
                Text(payload["context"] as? String ?: "They knocked on your Moment.", color = ViroColors.textMuted)
                responseError?.let { Text(it, color = ViroColors.consumerError) }
                TextButton(enabled = !busy && momentId != null && knockerId != null, onClick = {
                    busy = true
                    scope.launch {
                        repo.respondToKnock(momentId!!, knockerId!!, true)
                            .onSuccess { knock = null; onMessage(knockerId, knockerName) }
                            .onFailure { responseError = "Couldn't answer. Try again." }
                        busy = false
                    }
                }) { Text("Message") }
            } },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        if (momentId != null && knockerId != null) {
                            session.moments.respondToKnock(momentId, knockerId, true)
                                .onSuccess { knock = null; onCall(knockerId, null, knockerName) }
                                .onFailure { responseError = "Couldn't answer. Try again." }
                        }
                        busy = false
                    }
                }) { Text("Talk now", color = ViroColors.accent) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        if (momentId != null && knockerId != null) {
                            session.moments.respondToKnock(momentId, knockerId, false)
                                .onSuccess { knock = null }
                                .onFailure { responseError = "Couldn't dismiss. Try again." }
                        }
                        busy = false
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
    clock: () -> Long,
    onReturn: () -> Unit,
    onManage: () -> Unit,
) {
    val end = remember(own.expiresAt) { own.endsAt() }
    val left by remember(end) { derivedStateOf { remainingPhrase(remainingMinutes(end, clock())) } }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (ViroColors.isLight) ViroColors.surface else ViroColors.surfaceRaised.copy(alpha = 0.96f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (ViroColors.isLight) ViroColors.divider else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
                    left,
                    color = ViroColors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onReturn, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Return", color = ViroColors.accent, fontWeight = FontWeight.SemiBold)
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

/** An invitation still waiting on an answer, with a way to say no. */
@Composable
internal fun InvitationRow(moment: MomentDto, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = ViroColors.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 100.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(78.dp).clickable(onClick = onOpen)) {
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
                    listOfNotNull(
                        com.viroreach.app.moments.engine.MomentMood.of(moment.mood)?.aboutThem,
                        moment.activity(),
                    ).joinToString(" · "),
                    color = ViroColors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                moment.invitationText?.takeIf { it.isNotBlank() }?.let { words ->
                    Text(words, color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
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
internal fun WhoIsHere(m: MomentDto, color: androidx.compose.ui.graphics.Color) {
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
internal fun MomentPresenceEdge(intent: String, modifier: Modifier = Modifier) {
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

/**
 * The first thing asked when opening a Moment: how you are.
 *
 * A screen rather than a row in a form. It is the one part of creating a
 * Moment that is about the person instead of the arrangements, and the artwork
 * needs the room — squeezed into a list it was both unreadable and, being a
 * TextureView among recomposing siblings, the thing that crashed.
 *
 * Skippable in one tap. A Moment with no mood is perfectly ordinary and this
 * must never feel like a gate.
 */
@Composable
private fun MoodStep(
    selected: com.viroreach.app.moments.engine.MomentMood?,
    onPick: (com.viroreach.app.moments.engine.MomentMood?) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = ViroColors.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = ViroColors.textPrimary)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onNext) {
                        Text(
                            if (selected == null) "Skip" else "Next",
                            color = ViroColors.BlueAccent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                com.viroreach.app.moments.engine.MoodScreen(
                    selected = selected,
                    onPick = onPick,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                // Answering the question is enough to carry on. Waiting to be
                // told a second time, by a button under the picture, is what
                // made this feel like a form rather than a way in — and going
                // back to Now afterwards was plainly wrong: the answer is the
                // start of a Moment, so it opens one.
                //
                // The pause is for the face. It reacts to the press, and
                // leaving before it has is throwing away the one moment the
                // artwork exists for.
                LaunchedEffect(selected) {
                    if (selected != null) {
                        delay(900)
                        onNext()
                    }
                }
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (selected == null) "Tap how you are, or skip." else "Setting up your Moment…",
                        color = ViroColors.textMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Who to tell, once a Moment is open.
 *
 * Nobody is told anything without being chosen here. That is the whole point
 * of the step: how somebody is feeling is theirs, and a Moment that quietly
 * announced a mood to everybody in your contacts would be a betrayal dressed
 * as a feature. Nothing is preselected, and skipping is a real answer — a
 * Moment nobody was told about is still a Moment, and still findable by the
 * people it is visible to.
 *
 * Only accepted Viro connections appear, because the server will only accept
 * an invitation for one, and offering a name that would be refused is a lie
 * with an error message attached.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TellPeopleSheet(
    session: SessionManager,
    moment: MomentDto,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<List<com.viroreach.core.network.ConnectionDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { session.api.listConnections() }
            .onSuccess { list ->
                people = list.filter { it.status == "ACCEPTED" && it.peerUserId != null }
                loading = false
            }
            .onFailure {
                failed = "Couldn't load your connections."
                loading = false
            }
    }

    val mood = com.viroreach.app.moments.engine.MomentMood.of(moment.mood)
    val intent = com.viroreach.app.moments.engine.MomentIntent.of(moment.intent)

    ModalBottomSheet(
        onDismissRequest = { if (!sending) onDone() },
        containerColor = ViroColors.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Tell someone?", color = ViroColors.textPrimary, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            // What they will see, before anybody is chosen. Saying it plainly
            // here is the only way the choice is an informed one.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mood != null) {
                    com.viroreach.app.moments.engine.MoodTag(mood)
                    Text(" · ", color = ViroColors.textMuted)
                }
                Text(
                    intent?.label ?: "Time together",
                    color = ViroColors.textMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (mood != null) {
                    "They'll be told how you are and what you'd like — nothing else."
                } else {
                    "They'll be told what you'd like — nothing else."
                },
                color = ViroColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))

            when {
                loading -> Text("Looking…", color = ViroColors.textMuted)
                failed != null -> Text(failed ?: "", color = ViroColors.textMuted)
                people.isEmpty() -> Text(
                    "You have no Viro connections yet. Your Moment is open either way.",
                    color = ViroColors.textMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(people.size, key = { people[it].id }) { i ->
                        val person = people[i]
                        val id = person.peerUserId ?: return@items
                        val picked = id in chosen
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !sending) {
                                    chosen = if (picked) chosen - id else chosen + id
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ViroAvatar(
                                size = ViroAvatarSize.Small,
                                imageUrl = person.peerAvatarUrl,
                                displayName = person.peerDisplayName ?: "Viro user",
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                person.peerDisplayName ?: "Viro user",
                                color = ViroColors.textPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Checkbox(checked = picked, onCheckedChange = null, enabled = !sending)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDone, enabled = !sending) {
                    // Not "cancel": telling nobody is a legitimate answer, and
                    // the wording should not imply the Moment failed.
                    Text("Not now", color = ViroColors.textMuted)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = chosen.isNotEmpty() && !sending,
                    onClick = {
                        sending = true
                        scope.launch {
                            // One at a time, and a failure for one person does
                            // not cost the others theirs.
                            sendError = null
                            val unsent = chosen.filter { id -> session.moments.invite(moment.id, id).isFailure }.toSet()
                            chosen = unsent
                            sending = false
                            if (unsent.isEmpty()) onDone()
                            else sendError = "Couldn't tell ${unsent.size} people. Tap Tell to retry."
                        }
                    },
                ) {
                    Text(
                        if (chosen.isEmpty()) "Tell" else "Tell " + chosen.size,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            sendError?.let { Text(it, color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall) }
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
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    var duration by rememberSaveable { mutableIntStateOf(30) }
    var audience by rememberSaveable { mutableStateOf("CONNECTIONS") }
    var text by rememberSaveable { mutableStateOf("") }
    var invitation by rememberSaveable { mutableStateOf("") }
    var moodKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Asked once, first, and reachable again from the form afterwards.
    // Deliberately not saveable, for the same reason the sheet's own flag is
    // not: a step that outlives the sheet it belongs to comes back answered
    // for a Moment nobody has started yet.
    var showMood by remember { mutableStateOf(true) }
    if (showMood) {
        MoodStep(
            selected = com.viroreach.app.moments.engine.MomentMood.of(moodKey),
            onPick = { moodKey = it?.key },
            onBack = onDismiss,
            onNext = { showMood = false },
        )
    } else
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = ViroColors.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("What kind of Moment are you in?", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
                Text("Share what you're doing, or invite some company.", color = ViroColors.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (label in listOf("Studying", "Working")) {
                        FilterChip(selected = focus == label, onClick = {
                            focus = label; intentKey = "STAY"; somethingElse = false
                        }, label = { Text(label) })
                    }
                }
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
                            chosen = focus == null && !somethingElse && intentKey == intent.key,
                            modifier = Modifier.weight(1f),
                        ) { intentKey = intent.key; somethingElse = false; focus = null }
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
                    onClick = { somethingElse = true; focus = null },
                )
                if (somethingElse) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it.take(60) },
                        label = { Text("What would you like to do?") },
                        supportingText = { Text("${text.length}/60") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                }
            }
            item {
                // How they are is asked on a screen of its own, before this
                // one. What they say is asked here, because it is the reason
                // anyone walks in and does not belong buried under the
                // arrangements. Optional: somebody who says nothing gets a
                // plain line, not a sentence invented for them.
                com.viroreach.app.moments.engine.MomentMood.of(moodKey)?.let { mood ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("You're ", color = ViroColors.textMuted)
                        com.viroreach.app.moments.engine.MoodTag(mood)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { showMood = true }) {
                            Text("Change", color = ViroColors.BlueAccent)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
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
                            if (focus != null) {
                                CreateMomentBody("WORKING", focus, audience, duration, intent = "STAY",
                                    invitationText = invitation.trim().ifBlank { null }, mood = moodKey)
                            } else if (somethingElse) {
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
