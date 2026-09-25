package com.viroreach.app.consumer.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.viroreach.app.consumer.ChatRoute
import com.viroreach.app.consumer.resolveAvatarUrl
import com.viroreach.app.messaging.ChatMessage
import com.viroreach.app.messaging.Delivery
import com.viroreach.app.messaging.MessagingRepository
import com.viroreach.app.messaging.VoiceRecorder
import com.viroreach.app.messaging.ui.*
import com.viroreach.app.personalization.ProfilePhotoCapture
import com.viroreach.app.relationships.CommitmentDetector
import com.viroreach.app.relationships.ui.LoopCard
import com.viroreach.app.relationships.ui.LoopCreateDialog
import com.viroreach.app.relationships.ui.LoopHistoryDialog
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.components.ViroAvatar
import com.viroreach.core.designsystem.components.ViroAvatarSize
import com.viroreach.core.network.ImageDownscaler
import com.viroreach.core.network.LoopAnswerBody
import com.viroreach.core.network.LoopAnswerDto
import com.viroreach.core.network.LoopDto
import com.viroreach.core.network.LoopPeriodDto
import com.viroreach.core.network.RelationshipBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    session: SessionManager,
    route: ChatRoute,
    onBack: () -> Unit,
    onCall: (peerUserId: String?, phone: String?, name: String) -> Unit,
    onOpenRelationship: (peerUserId: String?, phone: String?, name: String) -> Unit,
    onOpenConversation: (ChatRoute) -> Unit,
    onOpenGroupInfo: (conversationId: String) -> Unit = {},
    onGroupCall: (memberIds: List<String>) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = session.messaging
    val me = remember { session.tokenStore.getUserId() }

    // ---- who and where ------------------------------------------------------
    var peerUserId by remember(route) { mutableStateOf(route.peerUserId) }
    var peerName by remember(route) { mutableStateOf(route.peerName) }
    var avatarUrl by remember(route) { mutableStateOf(route.peerAvatarUrl) }
    val phone = route.peerPhoneE164
    var convId by remember(route) { mutableStateOf(route.conversationId) }

    LaunchedEffect(route) {
        val contact = phone?.let { session.contactsRepository.findByPhone(it) }
            ?: peerUserId?.let { session.contactsRepository.findByUserId(it) }
            ?: session.contactsRepository.findByExactName(route.peerName)
        if (contact != null) {
            peerName = contact.effectiveDisplayName
            avatarUrl = avatarUrl ?: contact.resolveAvatarUrl()
            if (peerUserId == null) peerUserId = contact.userId
        }
        if (convId == null) convId = peerUserId?.let { repo.conversationIdFor(it) }
    }
    LaunchedEffect(Unit) {
        repo.conversationMoved.collect { (from, to) -> if (convId == from) convId = to }
    }
    LaunchedEffect(Unit) {
        repo.conversationGone.collect { gone ->
            if (gone == convId) {
                Toast.makeText(context, "This conversation was erased.", Toast.LENGTH_LONG).show()
                onBack()
            }
        }
    }
    val cid = convId
    DisposableEffect(cid) {
        if (cid != null) {
            repo.openChat(cid)
            session.messageNotifier.clear(cid)
        }
        onDispose {
            if (cid != null) repo.closeChat(cid)
            session.voicePlayer.stop()
        }
    }

    val messages by remember(cid) { cid?.let { repo.messages(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
        .collectAsState(initial = emptyList())
    val conversation by remember(cid) { cid?.let { repo.conversation(it) } ?: kotlinx.coroutines.flow.flowOf(null) }
        .collectAsState(initial = null)
    val typing by repo.typing.collectAsState()
    val present by repo.present.collectAsState()
    val overview by session.relationships.overview.collectAsState()
    val player by session.voicePlayer.state.collectAsState()
    val relationship = remember(overview, peerUserId, phone) { session.relationships.relationshipFor(peerUserId, phone) }
    val isPrivate = conversation?.kind == "PRIVATE"
    val isGroup = conversation?.kind == "GROUP"
    val vibe = if (isGroup) Vibe.DEFAULT else Vibe.of(relationship?.vibe)
    val features by repo.features.collectAsState()
    var memberNames by remember(cid) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(cid, conversation?.participantsCsv) {
        val c = conversation ?: return@LaunchedEffect
        if (c.kind != "GROUP") return@LaunchedEffect
        val server = repo.members(c.id).associate { it.userId to it.displayName }
        memberNames = c.participantsCsv.split(',').filter { it.isNotBlank() }.associateWith { id ->
            session.contactsRepository.nameForUserId(id) ?: server[id] ?: "Viro user"
        }
    }

    LaunchedEffect(Unit) { session.relationships.refresh() }

    // ---- lock ---------------------------------------------------------------
    var unlocked by remember(cid) { mutableStateOf(false) }
    if (conversation?.locked == true && !unlocked) {
        LockedChat(peerName, onUnlock = {
            ChatLock.authenticate(context, "Open chat with $peerName", onSuccess = { unlocked = true }, onFailure = {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            })
        }, onBack = onBack)
        return
    }

    // ---- presence -----------------------------------------------------------
    var peerPresence by remember(peerUserId) { mutableStateOf<String?>(null) }
    var peerLastSeen by remember(peerUserId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(peerUserId) {
        val target = peerUserId ?: return@LaunchedEffect
        while (true) {
            runCatching { session.api.getPresence(target) }.onSuccess {
                peerPresence = it.state
                peerLastSeen = com.viroreach.app.messaging.parseIso(it.lastSeenAt)
            }
            delay(30_000)
        }
    }

    // ---- local UI state -----------------------------------------------------
    val listState = rememberLazyListState()
    var loadingHistory by remember(cid) { mutableStateOf(false) }
    var historyExhausted by remember(cid) { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var reactionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var infoFor by remember { mutableStateOf<ChatMessage?>(null) }
    var forwardFrom by remember { mutableStateOf<ChatMessage?>(null) }
    var deleteFor by remember { mutableStateOf<ChatMessage?>(null) }
    var fullImage by remember { mutableStateOf<Pair<File, ChatMessage?>?>(null) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    var viewOnce by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var suggestion by remember { mutableStateOf<CommitmentDetector.Suggestion?>(null) }
    var suggestionMsgId by remember { mutableStateOf<String?>(null) }
    var photoPreview by remember { mutableStateOf<File?>(null) }
    var stickersOpen by remember { mutableStateOf(false) }
    var draftPreview by remember { mutableStateOf<com.viroreach.core.network.LinkPreviewDto?>(null) }
    var dismissedPreviewUrl by remember { mutableStateOf<String?>(null) }
    var draftUrl by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchTerm by remember { mutableStateOf("") }
    var searchIndex by remember { mutableIntStateOf(0) }
    var focusDone by remember(route.focusMessageId) { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(context, repo.media) }
    val effectsPlayed = remember { mutableSetOf<String>() }
    val openedAt = remember(cid) { System.currentTimeMillis() }
    val lastError by repo.lastError.collectAsState()

    LaunchedEffect(lastError) {
        lastError?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            repo.clearError()
        }
    }

    // ---- loops --------------------------------------------------------------
    var loops by remember(cid) { mutableStateOf<List<LoopDto>>(emptyList()) }
    // Encrypted Loop answers, once this phone has opened them.
    var openedAnswers by remember(cid) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loopDetail by remember { mutableStateOf<Pair<LoopDto, List<LoopPeriodDto>>?>(null) }
    var loopVoiceFor by remember { mutableStateOf<LoopDto?>(null) }
    var loopPhotoFor by remember { mutableStateOf<LoopDto?>(null) }
    suspend fun reloadLoops() {
        val id = convId ?: return
        loops = session.relationships.loops(id)
        // An encrypted answer arrives sealed and opens once, so it is opened
        // here — when the Loop is shown — and kept.
        val sealed = loops.flatMap { l ->
            (l.answers.orEmpty() + listOfNotNull(l.myAnswer)).filter { !it.envelopes.isNullOrEmpty() }
        }
        if (sealed.isNotEmpty()) {
            val next = openedAnswers.toMutableMap()
            sealed.forEach { answer ->
                if (next[answer.id] == null) {
                    repo.openLoopAnswer(answer)?.body?.let { next[answer.id] = it }
                }
            }
            openedAnswers = next
        }
    }
    LaunchedEffect(cid) { reloadLoops() }
    LaunchedEffect(cid) {
        repo.loopChanges.filter { it == convId }.collect { reloadLoops() }
    }

    // ---- heartbeat on arrival ------------------------------------------------
    LaunchedEffect(messages.lastOrNull()?.id) {
        val last = messages.lastOrNull() ?: return@LaunchedEffect
        if (!last.mine && last.effect == "heartbeat" && last.createdAt > openedAt - 5_000) heartbeatVibration(context)
    }

    // Next unheard voice note plays on after the current one.
    DisposableEffect(messages) {
        session.voicePlayer.nextAfter = { finishedId ->
            val idx = messages.indexOfFirst { it.id == finishedId }
            messages.drop(idx + 1).firstOrNull { it.type == "VOICE" && !it.mine && !it.viewOnce && !it.deleted }?.let { next ->
                (next.localMediaPath?.let { File(it) }?.takeIf { it.exists() } ?: next.media?.let { repo.media.cachedFile(it) })
                    ?.let { next.id to it }
            }
        }
        onDispose { session.voicePlayer.nextAfter = null }
    }

    LaunchedEffect(messages.lastOrNull()?.id, loops.size) {
        // The real count, after layout: day separators, Loop cards and the
        // typing row all add items, so an estimate could aim past the end.
        delay(50)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    fun nameOf(userId: String): String = when {
        userId == me -> "You"
        userId == peerUserId -> peerName
        else -> memberNames[userId] ?: "Someone"
    }

    // ---- sending ------------------------------------------------------------
    fun target(): String? = convId ?: peerUserId?.let { MessagingRepository.placeholder(it) }

    fun afterSendText(body: String, clientMsgId: String) {
        CommitmentDetector.detect(body)?.let {
            suggestion = it
            suggestionMsgId = clientMsgId
        }
    }

    fun sendOut(out: MessagingRepository.Outgoing) {
        val t = target() ?: return
        scope.launch {
            val cm = repo.send(t, out.copy(replyToId = replyTo?.id?.takeUnless { it.startsWith(MessagingRepository.LOCAL) }))
            replyTo = null
            if (out.type == "TEXT" && out.body != null) afterSendText(out.body, cm)
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { scope.launch { prepareImage(context, repo, it)?.let { f -> photoPreview = f } } }
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { u -> scope.launch { prepareImage(context, repo, u)?.let { f -> photoPreview = f } } }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            val u = ProfilePhotoCapture.createCameraUri(context)
            cameraUri = u
            takePhoto.launch(u)
        }
    }
    // Photo answers to a Loop.
    val pickLoopPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val loop = loopPhotoFor
        loopPhotoFor = null
        if (uri != null && loop != null) scope.launch {
            val f = prepareImage(context, repo, uri) ?: return@launch
            val dims = imageSize(f)
            runCatching {
                repo.loopAnswerBody(convId, "PHOTO", localFile = f, mime = "image/jpeg", width = dims.first, height = dims.second)
            }
                .onSuccess { body -> session.relationships.answerLoop(loop.id, body).onSuccess { reloadLoops() } }
                .onFailure { Toast.makeText(context, "Couldn't upload the photo.", Toast.LENGTH_SHORT).show() }
        }
    }

    // A link in the draft gets a preview card before it is sent — except in an
    // encrypted chat, where asking the server what a link looks like would
    // tell it which link is about to be sent. The link still goes, as text.
    LaunchedEffect(draftUrl, conversation?.encrypted) {
        val url = draftUrl
        if (url == null || url == dismissedPreviewUrl || conversation?.encrypted == true) {
            draftPreview = null
            return@LaunchedEffect
        }
        delay(500)
        draftPreview = repo.linkPreview(url)
    }
    // Arriving from search: jump to the message once it is loaded.
    LaunchedEffect(messages.size, route.focusMessageId) {
        val target = route.focusMessageId ?: return@LaunchedEffect
        if (focusDone) return@LaunchedEffect
        val idx = messages.indexOfFirst { it.id == target }
        if (idx >= 0) {
            delay(150)
            highlightId = target
            listState.scrollToItem(itemIndexOf(messages, idx))
            focusDone = true
        }
    }
    val searchMatches = remember(messages, searchTerm) {
        if (searchTerm.trim().length < 2) emptyList()
        else messages.filter { !it.deleted && (it.body?.contains(searchTerm.trim(), true) == true || it.poll?.question?.contains(searchTerm.trim(), true) == true) }
    }
    fun jumpToMatch(i: Int) {
        if (searchMatches.isEmpty()) return
        searchIndex = ((i % searchMatches.size) + searchMatches.size) % searchMatches.size
        val m = searchMatches[searchMatches.size - 1 - searchIndex]
        highlightId = m.id
        val idx = messages.indexOfFirst { it.id == m.id }
        if (idx >= 0) scope.launch { listState.animateScrollToItem(itemIndexOf(messages, idx)) }
    }
    LaunchedEffect(searchMatches) { if (searchMatches.isNotEmpty()) jumpToMatch(0) }

    val pickGif = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val mime = context.contentResolver.getType(uri) ?: ""
            if (mime == "image/gif") {
                val f = repo.media.newOutgoingFile("gif")
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                        f.length() in 1..(5L * 1024 * 1024)
                    }.getOrDefault(false)
                }
                if (!ok) {
                    f.delete()
                    Toast.makeText(context, "That GIF is too large (5 MB at most).", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val dims = imageSize(f)
                sendOut(MessagingRepository.Outgoing(type = "IMAGE", localFile = f, mime = "image/gif", width = dims.first, height = dims.second))
                stickersOpen = false
            } else {
                prepareImage(context, repo, uri)?.let { photoPreview = it }
            }
        }
    }

    // Location: asked for only when someone actually shares one.
    fun shareLocation(liveSeconds: Int?) {
        scope.launch {
            if (!com.viroreach.app.messaging.location.ViroLocation.enabled(context)) {
                Toast.makeText(context, "Turn on location on this phone first.", Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(context, "Finding your location…", Toast.LENGTH_SHORT).show()
            val fix = com.viroreach.app.messaging.location.ViroLocation.current(context)
            if (fix == null) {
                Toast.makeText(context, "Couldn't find your location. Try again outside or near a window.", Toast.LENGTH_LONG).show()
                return@launch
            }
            sendOut(
                MessagingRepository.Outgoing(
                    type = "LOCATION",
                    location = com.viroreach.core.network.LocationBody(
                        lat = fix.latitude,
                        lng = fix.longitude,
                        accuracy = fix.accuracy.takeIf { it > 0f }?.toDouble(),
                        liveSeconds = liveSeconds,
                    ),
                ),
            )
            if (liveSeconds != null) com.viroreach.app.messaging.location.LiveLocationService.start(context)
        }
    }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) dialog = "location"
        else Toast.makeText(context, "Viro needs location permission to share where you are.", Toast.LENGTH_LONG).show()
    }

    // A document: copied into Viro's own storage first, because the picked
    // Uri is only readable while this screen holds the grant.
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val picked = readDocument(context, repo, uri)
            when {
                picked == null -> Toast.makeText(context, "Couldn't read that file.", Toast.LENGTH_SHORT).show()
                picked.file.length() > MAX_DOCUMENT_BYTES -> {
                    picked.file.delete()
                    Toast.makeText(context, "That file is too large (25 MB at most).", Toast.LENGTH_LONG).show()
                }
                else -> sendOut(
                    MessagingRepository.Outgoing(
                        type = "FILE",
                        localFile = picked.file,
                        mime = picked.mime,
                        fileName = picked.name,
                    ),
                )
            }
        }
    }

    // ---- layout -------------------------------------------------------------
    BackHandler {
        when {
            stickersOpen -> stickersOpen = false
            searchOpen -> { searchOpen = false; searchTerm = "" }
            else -> onBack()
        }
    }
    Box(Modifier.fillMaxSize().background(ViroColors.background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            if (searchOpen) {
                Row(Modifier.fillMaxWidth().background(ViroColors.surface).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { searchOpen = false; searchTerm = "" }) { Icon(Icons.Default.ArrowBack, "Close search", tint = ViroColors.textPrimary) }
                    OutlinedTextField(
                        value = searchTerm, onValueChange = { searchTerm = it.take(100) }, singleLine = true,
                        placeholder = { Text("Search in this chat") }, modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = ViroColors.textPrimary, unfocusedTextColor = ViroColors.textPrimary),
                    )
                    Text(if (searchMatches.isEmpty()) "0" else "${searchIndex + 1}/${searchMatches.size}", color = ViroColors.textSecondary, modifier = Modifier.padding(horizontal = 6.dp))
                    IconButton(onClick = { jumpToMatch(searchIndex + 1) }) { Icon(Icons.Default.KeyboardArrowUp, "Older match", tint = ViroColors.textPrimary) }
                    IconButton(onClick = { jumpToMatch(searchIndex - 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Newer match", tint = ViroColors.textPrimary) }
                }
            } else ChatHeader(
                name = if (isGroup) conversation?.title ?: peerName else peerName,
                avatarUrl = avatarUrl,
                vibe = vibe,
                isGroup = isGroup,
                subtitle = when {
                    isGroup && cid != null && typing[cid] != null -> "${nameOf(typing[cid]!!.userId)} is ${if (typing[cid]!!.state == "recording") "recording…" else "typing…"}"
                    isGroup -> memberNames.values.filter { it != "You" }.take(4).joinToString(", ").ifBlank { null } ?: "${memberNames.size} members"
                    cid != null && typing[cid]?.state == "recording" -> "recording voice…"
                    cid != null && typing[cid]?.state == "typing" -> "typing…"
                    isPrivate -> "Private · ends ${remaining(conversation?.expiresAt)}"
                    cid != null && present.containsKey(cid) -> "💫 Together now"
                    peerPresence == "ONLINE" -> "Online"
                    peerPresence == "BUSY" -> "On a call"
                    // Only when they share it; otherwise nothing is said at all.
                    peerLastSeen != null -> com.viroreach.app.people.lastSeenLabel(peerLastSeen)
                    else -> relationship?.let { labelOf(it.relationshipType, it.customLabel) }
                },
                subtitleActive = cid != null && (typing.containsKey(cid) || present.containsKey(cid)),
                onBack = onBack,
                onTitle = { if (isGroup) cid?.let(onOpenGroupInfo) else onOpenRelationship(peerUserId, phone, peerName) },
                onCall = when {
                    isGroup -> ({ onGroupCall(memberNames.keys.filter { it != me }) })
                    peerUserId != null -> ({ onCall(peerUserId, phone, peerName) })
                    else -> null
                },
                onHeartbeat = if (!isGroup && vibe.heartbeat && peerUserId != null) ({
                    heartbeatVibration(context)
                    sendOut(MessagingRepository.Outgoing(body = "💓 Thinking of you", effect = "heartbeat"))
                }) else null,
                menuOpen = menuOpen,
                onMenu = { menuOpen = it },
                menu = {
                    ChatMenu(
                        isGroup = isGroup,
                        isPrivate = isPrivate,
                        locked = conversation?.locked == true,
                        hidden = conversation?.hidden == true,
                        muted = (conversation?.mutedUntil ?: 0L) > System.currentTimeMillis(),
                        disappearing = conversation?.disappearingSeconds,
                        hasConversation = cid != null && !cid.startsWith(MessagingRepository.PLACEHOLDER),
                        hasPeer = peerUserId != null && !isGroup,
                        encrypted = conversation?.encrypted == true,
                        onPick = { which ->
                            menuOpen = false
                            dialog = which
                        },
                    )
                },
            )

            // A quiet line of context: "Tomorrow is your anniversary ❤️".
            relationship?.flags?.firstOrNull { it.code in setOf("DATE_SOON", "COMMITMENT_DUE", "LOOP_WAITING", "FOLLOW_UP_DUE", "DUE_TODAY") }?.let { flag ->
                ContextStrip(flag.text, vibe) { onOpenRelationship(peerUserId, phone, peerName) }
            }
            if (conversation?.encrypted == true) {
                // Their keys changed since this phone first saw them. It is
                // usually a reinstall — and it is the one thing worth
                // interrupting for, because it is also what being listened to
                // would look like.
                var codeChanged by remember(peerUserId) { mutableStateOf(false) }
                LaunchedEffect(peerUserId, conversation?.encrypted) {
                    codeChanged = peerUserId?.let { repo.e2ee.identityChanged(it) } == true
                }
                if (codeChanged) {
                    ContextStrip("$peerName's security code changed — tap to check", vibe) { dialog = "encryption" }
                }
                // Said once, quietly, where WhatsApp says it: this is a claim
                // the person can check against the safety number in Details.
                Text(
                    "🔒 Messages here are end-to-end encrypted",
                    color = ViroColors.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            conversation?.disappearingSeconds?.let { secs ->
                Text(
                    "⏱ Messages disappear after ${disappearingLabel(secs)}",
                    color = ViroColors.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            val pinned = conversation?.pinnedCsv?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            messages.lastOrNull { it.id in pinned }?.let { p ->
                PinnedBar(p, vibe) {
                    highlightId = p.id
                    scope.launch {
                        val i = messages.indexOfFirst { it.id == p.id }
                        if (i >= 0) listState.animateScrollToItem(itemIndexOf(messages, i))
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item(key = "older-history") {
                    if (!historyExhausted && cid != null && !cid.startsWith("peer:")) {
                        TextButton(
                            enabled = !loadingHistory,
                            onClick = {
                                loadingHistory = true
                                scope.launch {
                                    repo.loadOlder(cid, messages.minOfOrNull { it.createdAt } ?: System.currentTimeMillis())
                                        .onSuccess { historyExhausted = it < 60 }
                                        .onFailure { Toast.makeText(context, "Couldn't load older messages. Try again.", Toast.LENGTH_SHORT).show() }
                                    loadingHistory = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loadingHistory) "Loading history…" else "Load older messages") }
                    }
                }
                if (messages.isEmpty() && loops.isEmpty()) {
                    item {
                        EmptyChat(peerName, isPrivate, peerUserId != null, vibe)
                    }
                }
                var lastDay: LocalDate? = null
                messages.forEachIndexed { i, msg ->
                    val day = Date(msg.createdAt).toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                    if (day != lastDay) {
                        val d = day
                        item(key = "day-$d") { DaySeparator(dayLabel(d)) }
                        lastDay = day
                    }
                    item(key = msg.id) {
                        val play = msg.effect != null && msg.createdAt > openedAt - 5_000 && effectsPlayed.add(msg.id)
                        MessageRow(
                            msg = msg,
                            vibe = vibe,
                            senderLabel = ::nameOf,
                            delivery = msg.delivery(conversation?.peerLastDeliveredAt, conversation?.peerLastReadAt),
                            media = repo.media,
                            player = player,
                            highlighted = highlightId == msg.id,
                            playEffect = play,
                            groupSender = if (isGroup) nameOf(msg.senderUserId) else null,
                            transcriptsEnabled = features.transcripts == true,
                            callbacks = BubbleCallbacks(
                                onLongPress = { actionsFor = it },
                                onReply = { if (!it.isPending) replyTo = it },
                                onRetry = { m -> m.clientMsgId?.let { repo.retry(it) } },
                                onToggleVoice = { m, f -> session.voicePlayer.toggle(m.id, f) },
                                onOpenImage = { m, f -> fullImage = f to m },
                                onOpenViewOnce = { m ->
                                    scope.launch {
                                        val f = m.media?.let { repo.media.fetch(it) } ?: return@launch
                                        if (m.type == "VOICE") session.voicePlayer.play(m.id, f) else fullImage = f to m
                                        repo.markViewed(m.id)
                                    }
                                },
                                onReactionTap = { reactionsFor = it },
                                onOpenLoop = { loopId ->
                                    scope.launch {
                                        val l = loops.firstOrNull { it.id == loopId } ?: return@launch
                                        session.relationships.loopHistory(loopId).onSuccess { loopDetail = l to it }
                                    }
                                },
                                onVote = { m, options -> scope.launch { repo.vote(m.id, options) } },
                                onTranscribe = { m ->
                                    scope.launch {
                                        repo.transcribe(m.id).onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                                    }
                                },
                                onOpenFile = { m ->
                                    scope.launch {
                                        val f = m.media?.let { repo.media.fetch(it) }
                                        if (f == null) {
                                            Toast.makeText(context, "Couldn't download that file.", Toast.LENGTH_SHORT).show()
                                        } else if (!openDocument(context, f, m.media?.mime)) {
                                            Toast.makeText(context, "No app on this phone opens that kind of file.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                onMessageContact = { card ->
                                    card.userId?.let { id ->
                                        onOpenConversation(
                                            ChatRoute(conversationId = null, peerName = card.name, peerUserId = id, peerPhoneE164 = card.phones.firstOrNull()),
                                        )
                                    }
                                },
                                onCallContact = { card ->
                                    card.userId?.let { id -> onCall(id, card.phones.firstOrNull(), card.name) }
                                },
                                onSaveContact = { card -> saveContactToPhone(context, card) },
                                onOpenPlace = { place ->
                                    if (!com.viroreach.app.messaging.location.ViroLocation.openInMaps(context, place.lat, place.lng, place.label)) {
                                        Toast.makeText(context, "No maps app on this phone.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onStopSharingLocation = { m -> scope.launch { repo.stopLiveLocation(m.id) } },
                                onJumpTo = { id ->
                                    val idx = messages.indexOfFirst { it.id == id }
                                    if (idx >= 0) {
                                        highlightId = id
                                        scope.launch { listState.animateScrollToItem(itemIndexOf(messages, idx)) }
                                    }
                                },
                            ),
                        )
                    }
                }
                items(loops.filter { it.active == true && it.periodKey != null && it.revealed != true }, key = { "loop-${it.id}" }) { loop ->
                    Box(Modifier.padding(vertical = 6.dp)) {
                        LoopCard(
                            loop = loop,
                            myUserId = me,
                            vibe = vibe,
                            nameOf = ::nameOf,
                            onAnswerText = { l, kind, text ->
                                scope.launch {
                                    val body = repo.loopAnswerBody(convId, kind, text = text)
                                    session.relationships.answerLoop(l.id, body)
                                        .onSuccess { reloadLoops() }
                                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                                }
                            },
                            onAnswerVoice = { loopVoiceFor = it },
                            onAnswerPhoto = {
                                loopPhotoFor = it
                                pickLoopPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            openedAnswers = openedAnswers,
                            onPlayAnswer = { a -> scope.launch { playLoopAnswer(a, repo, session, { f -> fullImage = f to null }) } },
                            onMore = { l ->
                                scope.launch { session.relationships.loopHistory(l.id).onSuccess { loopDetail = l to it } }
                            },
                        )
                    }
                }
                cid?.let { id ->
                    typing[id]?.let { t ->
                        item(key = "typing") {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                TypingDots(vibe.accent)
                                Spacer(Modifier.width(8.dp))
                                Text(if (t.state == "recording") "$peerName is recording a voice note" else "$peerName is typing", color = ViroColors.textSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            suggestion?.let { s ->
                CommitmentChip(s, vibe, onTap = { dialog = "commitment" }, onDismiss = { suggestion = null })
            }

            Composer(
                vibe = vibe,
                recorder = recorder,
                replyTo = replyTo,
                replyAuthor = replyTo?.let { if (it.mine) "yourself" else peerName }.orEmpty(),
                editing = editing,
                enabled = peerUserId != null || isPrivate || (cid != null && !cid.startsWith(MessagingRepository.PLACEHOLDER)),
                disabledReason = "$peerName isn't on Viro yet. Invite them to message.",
                viewOnce = viewOnce,
                onToggleViewOnce = { viewOnce = !viewOnce },
                onCancelReply = { replyTo = null },
                onCancelEdit = { editing = null },
                onTyping = { state -> cid?.let { repo.sendTyping(it, state) } },
                onDraftChanged = { text -> draftUrl = URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', '!', '?') },
                aboveInput = draftPreview?.let { p ->
                    {
                        Box(Modifier.padding(horizontal = 12.dp)) {
                            LinkPreviewCard(p, repo.media, mine = true, onClose = {
                                dismissedPreviewUrl = draftUrl
                                draftPreview = null
                            })
                        }
                    }
                },
                stickersOpen = stickersOpen,
                // Naming someone with @ only means something in a group.
                mentionable = if (isGroup) {
                    memberNames.filter { (id, _) -> id != me }
                        .map { (id, name) -> com.viroreach.app.messaging.ui.MentionTarget(id, name) }
                        .sortedBy { it.name.lowercase() }
                } else {
                    emptyList()
                },
                onAction = { a ->
                    when (a) {
                        is ComposerAction.Text -> {
                            val preview = draftPreview?.takeIf { p -> a.body.contains(p.url) || draftUrl?.let { a.body.contains(it) } == true }
                            sendOut(
                                MessagingRepository.Outgoing(
                                    body = a.body,
                                    deliverAt = a.deliverAt,
                                    effect = a.effect,
                                    linkPreview = preview,
                                    mentions = a.mentions,
                                ),
                            )
                            draftPreview = null
                            draftUrl = null
                        }
                        ComposerAction.CreatePoll -> dialog = "poll"
                        ComposerAction.OpenStickers -> stickersOpen = !stickersOpen
                        is ComposerAction.Edit -> scope.launch {
                            repo.edit(a.messageId, a.body)
                            editing = null
                        }
                        is ComposerAction.Voice -> sendOut(
                            MessagingRepository.Outgoing(
                                type = "VOICE",
                                localFile = a.recording.file,
                                mime = a.recording.mime,
                                durationMs = a.recording.durationMs,
                                waveform = a.recording.waveform,
                                viewOnce = a.viewOnce,
                            ),
                        ).also { viewOnce = false }
                        ComposerAction.PickPhoto -> pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        ComposerAction.TakePhoto -> cameraPermission.launch(android.Manifest.permission.CAMERA)
                        ComposerAction.SendDocument -> runCatching { pickDocument.launch(arrayOf("*/*")) }
                            .onFailure { Toast.makeText(context, "No file picker on this phone.", Toast.LENGTH_SHORT).show() }
                        ComposerAction.ShareContact -> dialog = "share_contact"
                        ComposerAction.ShareLocation ->
                            if (com.viroreach.app.messaging.location.ViroLocation.hasPermission(context)) dialog = "location"
                            else locationPermission.launch(
                                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
                            )
                    }
                },
            )
            if (stickersOpen) {
                StickerGifPanel(
                    repo = repo,
                    vibe = vibe,
                    onSticker = { st ->
                        sendOut(MessagingRepository.Outgoing(type = "STICKER", body = st.emoji, sticker = com.viroreach.core.network.StickerRef(st.pack, st.id)))
                    },
                    onGif = { g ->
                        stickersOpen = false
                        sendOut(
                            MessagingRepository.Outgoing(
                                type = "GIF",
                                gif = com.viroreach.core.network.GifSendDto(g.url, g.previewUrl, g.width, g.height, g.provider),
                            ),
                        )
                    },
                    onGifFromGallery = { pickGif.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onClose = { stickersOpen = false },
                )
            }
            if (peerUserId == null && !isPrivate && !isGroup) {
                TextButton(
                    onClick = { inviteToViro(context, peerName) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Invite $peerName to Viro", color = vibe.accent) }
            }
        }

        fullImage?.let { (f, _) -> FullImage(f) { fullImage = null } }
    }

    // ---- sheets & dialogs ------------------------------------------------------
    actionsFor?.let { m ->
        MessageActionsSheet(
            msg = m,
            vibe = vibe,
            onDismiss = { actionsFor = null },
            onReact = { emoji ->
                actionsFor = null
                val mine = m.reactions.firstOrNull { it.userId == me }?.emoji
                scope.launch { repo.react(m.id, if (mine == emoji) null else emoji) }
            },
            onAction = { action ->
                actionsFor = null
                when (action) {
                    "reply" -> replyTo = m
                    "copy" -> copyText(context, m.body.orEmpty())
                    "edit" -> editing = m
                    "delete" -> deleteFor = m
                    "forward" -> forwardFrom = m
                    "star" -> scope.launch { repo.star(m.id, !m.starred) }
                    "pin" -> scope.launch { repo.pin(m.id, m.id !in (conversation?.pinnedCsv?.split(',') ?: emptyList())) }
                    "info" -> infoFor = m
                    "remind" -> {
                        suggestion = CommitmentDetector.detect(m.body.orEmpty())
                            ?: CommitmentDetector.Suggestion(CommitmentDetector.Kind.OTHER, m.body.orEmpty().take(120), LocalDateTime.now().plusHours(3), "Add commitment", "later today")
                        suggestionMsgId = m.id
                        dialog = "commitment"
                    }
                }
            },
            pinned = m.id in (conversation?.pinnedCsv?.split(',') ?: emptyList()),
        )
    }
    reactionsFor?.let { m ->
        AlertDialog(
            onDismissRequest = { reactionsFor = null },
            title = { Text("Reactions") },
            text = {
                Column {
                    m.reactions.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.emoji, fontSize = 22.sp)
                            Spacer(Modifier.width(12.dp))
                            Text(nameOf(r.userId), modifier = Modifier.weight(1f))
                            if (r.userId == me) TextButton(onClick = {
                                reactionsFor = null
                                scope.launch { repo.react(m.id, null) }
                            }) { Text("Remove") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { reactionsFor = null }) { Text("Close") } },
        )
    }
    deleteFor?.let { m ->
        val canEveryone = m.mine && !m.deleted && System.currentTimeMillis() - m.createdAt < 48 * 3600_000L
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete message?") },
            text = { Text(if (canEveryone) "Delete for everyone removes it from both phones." else "It will be removed from this chat on your phones.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (canEveryone) TextButton(onClick = {
                        deleteFor = null
                        scope.launch { repo.delete(m.id, forEveryone = true) }
                    }) { Text("Delete for everyone", color = ViroColors.consumerError) }
                    TextButton(onClick = {
                        deleteFor = null
                        scope.launch { repo.delete(m.id, forEveryone = false) }
                    }) { Text("Delete for me") }
                    TextButton(onClick = { deleteFor = null }) { Text("Cancel") }
                }
            },
        )
    }
    infoFor?.let { m ->
        val d = m.delivery(conversation?.peerLastDeliveredAt, conversation?.peerLastReadAt)
        AlertDialog(
            onDismissRequest = { infoFor = null },
            title = { Text("Message info") },
            text = {
                Column {
                    Text("Sent ${fullTime(m.createdAt)}")
                    if (m.editedAt != null) Text("Edited ${fullTime(m.editedAt!!)}")
                    if (m.mine) Text(
                        when (d) {
                            Delivery.READ -> "Read ${conversation?.peerLastReadAt?.let { fullTime(it) } ?: ""}"
                            Delivery.DELIVERED -> "Delivered ${conversation?.peerLastDeliveredAt?.let { fullTime(it) } ?: ""}"
                            Delivery.SCHEDULED -> "Scheduled for ${m.deliverAt?.let { fullTime(it) }}"
                            Delivery.SENDING -> "Sending"
                            Delivery.FAILED -> "Not sent"
                            else -> "Sent"
                        },
                    )
                    m.expiresAt?.let { Text("Disappears ${fullTime(it)}") }
                }
            },
            confirmButton = { TextButton(onClick = { infoFor = null }) { Text("OK") } },
        )
    }
    forwardFrom?.let { m ->
        ForwardDialog(session, onDismiss = { forwardFrom = null }) { target ->
            forwardFrom = null
            scope.launch {
                val dest = target.conversationId ?: target.peerUserId?.let { repo.conversationIdFor(it) } ?: return@launch
                val file = m.media?.let { repo.media.fetch(it) }
                repo.send(
                    dest,
                    MessagingRepository.Outgoing(
                        type = m.type,
                        body = m.body,
                        localFile = file,
                        mime = m.media?.mime,
                        durationMs = m.media?.durationMs,
                        waveform = m.media?.waveform,
                        width = m.media?.width,
                        height = m.media?.height,
                        forwarded = true,
                    ),
                )
                Toast.makeText(context, "Forwarded to ${target.peerName}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    photoPreview?.let { f ->
        PhotoSendDialog(f, vibe, viewOnce, onToggleViewOnce = { viewOnce = !viewOnce }, onDismiss = { photoPreview = null; f.delete() }) { caption ->
            photoPreview = null
            val dims = imageSize(f)
            sendOut(
                MessagingRepository.Outgoing(
                    type = "IMAGE", body = caption.ifBlank { null }, localFile = f, mime = "image/jpeg",
                    width = dims.first, height = dims.second, viewOnce = viewOnce,
                ),
            )
            viewOnce = false
        }
    }
    loopDetail?.let { (loop, periods) ->
        LoopHistoryDialog(
            loop = loop, periods = periods, myUserId = me, nameOf = ::nameOf, vibe = vibe,
            onPlay = { a -> scope.launch { playLoopAnswer(a, repo, session, { f -> fullImage = f to null }) } },
            onPauseToggle = {
                loopDetail = null
                scope.launch {
                    session.relationships.setLoopActive(loop.id, loop.active != true)
                    reloadLoops()
                }
            },
            onDelete = if (loop.createdBy == me) ({
                loopDetail = null
                scope.launch {
                    session.relationships.deleteLoop(loop.id)
                    reloadLoops()
                }
            }) else null,
            onDismiss = { loopDetail = null },
        )
    }
    loopVoiceFor?.let { loop ->
        LoopVoiceDialog(recorder, vibe, onDismiss = { loopVoiceFor = null }) { rec ->
            loopVoiceFor = null
            scope.launch {
                runCatching {
                    repo.loopAnswerBody(convId, "VOICE", localFile = rec.file, mime = rec.mime, durationMs = rec.durationMs, waveform = rec.waveform)
                }
                    .onSuccess { body -> session.relationships.answerLoop(loop.id, body).onSuccess { reloadLoops() } }
                    .onFailure { Toast.makeText(context, "Couldn't upload your answer.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    when (dialog) {
        "location" -> ShareLocationDialog(
            onDismiss = { dialog = null },
            onSendOnce = {
                dialog = null
                shareLocation(null)
            },
            onShareLive = { seconds ->
                dialog = null
                shareLocation(seconds)
            },
        )
        "share_contact" -> ShareContactDialog(
            session = session,
            onDismiss = { dialog = null },
            onPick = { card ->
                dialog = null
                sendOut(MessagingRepository.Outgoing(type = "CONTACT", body = card.name, contact = card.toBody()))
            },
        )
        "poll" -> PollCreateDialog(vibe, onDismiss = { dialog = null }) { body ->
            dialog = null
            sendOut(MessagingRepository.Outgoing(type = "POLL", poll = body))
        }
        "vibe" -> VibeDialog(vibe, onDismiss = { dialog = null }) { chosen ->
            dialog = null
            scope.launch {
                session.relationships.save(
                    RelationshipBody(subjectUserId = peerUserId, subjectPhone = phone, displayName = peerName, vibe = chosen.key),
                ).onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
            }
        }
        "loop" -> LoopCreateDialog(
            vibe = vibe,
            suggestedGroup = when (vibe) {
                Vibe.CLOSE -> "Couple"
                Vibe.FAMILY -> "Family"
                Vibe.WORK -> "Work"
                else -> "Friends"
            },
            encryptedChat = conversation?.encrypted == true,
            onDismiss = { dialog = null },
        ) { body ->
            dialog = null
            scope.launch {
                val c = convId?.takeUnless { it.startsWith(MessagingRepository.PLACEHOLDER) }
                session.relationships.createLoop(body.copy(conversationId = c, toUserId = if (c == null) peerUserId else null))
                    .onSuccess {
                        repo.syncNow()
                        if (convId?.startsWith(MessagingRepository.PLACEHOLDER) != false) convId = it.conversationId
                        reloadLoops()
                    }
                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
            }
        }
        "disappearing" -> ChoiceDialog(
            "Disappearing messages",
            "New messages disappear for both of you after the time you choose.",
            listOf("Off" to null, "24 hours" to 86400, "7 days" to 604800, "90 days" to 7776000),
            onDismiss = { dialog = null },
        ) { secs ->
            dialog = null
            convId?.let { id -> scope.launch { repo.setDisappearing(id, secs) } }
        }
        "private" -> ChoiceDialog(
            "Private session",
            "A separate conversation that is permanently deleted for both of you when the time is up.",
            listOf("1 hour" to 3600, "6 hours" to 21600, "24 hours" to 86400, "7 days" to 604800),
            onDismiss = { dialog = null },
        ) { secs ->
            dialog = null
            val peer = peerUserId ?: return@ChoiceDialog
            scope.launch {
                repo.startPrivate(peer, secs ?: 3600).onSuccess { id ->
                    onOpenConversation(ChatRoute(conversationId = id, peerName = peerName, peerUserId = peer, peerPhoneE164 = phone, peerAvatarUrl = avatarUrl))
                }
            }
        }
        "mute" -> ChoiceDialog(
            "Mute notifications",
            null,
            listOf("Unmute" to null, "8 hours" to 8 * 3600, "1 week" to 7 * 86400, "Always" to 3650 * 86400),
            onDismiss = { dialog = null },
        ) { secs ->
            dialog = null
            convId?.let { id -> scope.launch { repo.setMuted(id, secs?.let { System.currentTimeMillis() + it * 1000L }) } }
        }
        "encryption" -> EncryptionDialog(repo, peerUserId, peerName) { dialog = null }
        "starred" -> StarredDialog(messages.filter { it.starred }, onDismiss = { dialog = null }) { id ->
            dialog = null
            val idx = messages.indexOfFirst { it.id == id }
            if (idx >= 0) {
                highlightId = id
                scope.launch { listState.animateScrollToItem(itemIndexOf(messages, idx)) }
            }
        }
        "clear" -> ConfirmDialog(
            "Delete chat?", "Removes this conversation from your phones. $peerName keeps their copy.", "Delete chat",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            convId?.let { id -> scope.launch { repo.clearForMe(id); onBack() } }
        }
        "reset" -> ConfirmDialog(
            "Reset chat?", "Wipes every message for both of you and keeps $peerName as a contact, so you start again from zero. $peerName will see that you reset it.", "Reset for both",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            convId?.let { id -> scope.launch { repo.reset(id) } }
        }
        "erase" -> ConfirmDialog(
            "Erase & disconnect?", "Permanently deletes every conversation you share with $peerName, for both of you, and removes your Viro connection. This cannot be undone.", "Erase everything",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            peerUserId?.let { p -> scope.launch { repo.eraseWith(p); onBack() } }
        }
        "endprivate" -> ConfirmDialog(
            "End private session?", "Deletes this session for both of you now.", "End now",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            convId?.let { id -> scope.launch { repo.endPrivate(id); onBack() } }
        }
        "commitment" -> suggestion?.let { s ->
            CommitmentDialog(s, peerName, vibe, onDismiss = { dialog = null }) { text, due ->
                dialog = null
                suggestion = null
                scope.launch {
                    val msgId = suggestionMsgId?.let { id -> if (id.contains('-') && !id.startsWith("local:")) repo.message(id)?.id ?: id else id }
                    session.relationships.addCommitment(
                        text = text, due = due, kind = s.kind.name,
                        subjectUserId = peerUserId, subjectPhone = phone, displayName = peerName,
                        conversationId = convId, messageId = msgId,
                    ).onSuccess {
                        Toast.makeText(context, "Viro will remind you ${s.whenLabel}.", Toast.LENGTH_SHORT).show()
                    }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                }
            }
        } ?: LaunchedEffect(Unit) { dialog = null }
    }

    // Menu choices that act rather than show a dialog.
    LaunchedEffect(dialog) {
        when (dialog) {
            "lock" -> {
                dialog = null
                val id = convId ?: return@LaunchedEffect
                val lockedNow = conversation?.locked == true
                ChatLock.authenticate(context, if (lockedNow) "Unlock chat" else "Lock chat", onSuccess = {
                    scope.launch { repo.setLocked(id, !lockedNow) }
                }, onFailure = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() })
            }
            "hide" -> {
                dialog = null
                val id = convId ?: return@LaunchedEffect
                val hide = conversation?.hidden != true
                repo.setHidden(id, hide)
                if (hide) {
                    Toast.makeText(context, "Chat hidden. Find it under Hidden chats.", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }
            "relationship" -> {
                dialog = null
                onOpenRelationship(peerUserId, phone, peerName)
            }
            "groupinfo" -> {
                dialog = null
                cid?.let(onOpenGroupInfo)
            }
            "search" -> {
                dialog = null
                searchOpen = true
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

private val URL_REGEX = Regex("""https?://[^\s<>"]{4,}""", RegexOption.IGNORE_CASE)

private fun listItemsCount(messages: List<ChatMessage>, loops: List<LoopDto>): Int {
    val days = messages.map { Date(it.createdAt).toInstant().atZone(ZoneId.systemDefault()).toLocalDate() }.distinct().size
    val openLoops = loops.count { it.active == true && it.periodKey != null && it.revealed != true }
    return (messages.size + days + openLoops).coerceAtLeast(1)
}

/** List position of message [i], counting the day separators before it. */
private fun itemIndexOf(messages: List<ChatMessage>, i: Int): Int {
    val days = messages.take(i + 1).map { Date(it.createdAt).toInstant().atZone(ZoneId.systemDefault()).toLocalDate() }.distinct().size
    return i + days + 1 // The history loader is the first list item.
}

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy"))
    }
}

private fun fullTime(ms: Long) = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

private fun remaining(expiresAt: Long?): String {
    val left = (expiresAt ?: return "soon") - System.currentTimeMillis()
    if (left <= 0) return "now"
    val mins = left / 60_000
    return when {
        mins < 60 -> "in ${mins.coerceAtLeast(1)} min"
        mins < 48 * 60 -> "in ${mins / 60} h"
        else -> "in ${mins / 1440} days"
    }
}

private fun disappearingLabel(secs: Int) = when (secs) {
    86400 -> "24 hours"
    604800 -> "7 days"
    7776000 -> "90 days"
    else -> "${secs / 3600} hours"
}

fun labelOf(type: String?, custom: String?): String? = custom ?: when (type) {
    null -> null
    "BEST_FRIEND" -> "Best friend"
    "BUSINESS_PARTNER" -> "Business partner"
    else -> type.lowercase().replaceFirstChar { it.uppercase() }
}

private fun heartbeatVibration(context: Context) {
    val v = context.getSystemService(Vibrator::class.java) ?: return
    val pattern = longArrayOf(0, 90, 120, 150)
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun inviteToViro(context: Context, name: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Hi $name, let's talk on Viro — calls and messages that keep us close. https://reach.viro3.online")
    }
    context.startActivity(Intent.createChooser(intent, "Invite to Viro"))
}

/** Downscales a chosen photo to 1600 px JPEG in app storage. */
private suspend fun prepareImage(context: Context, repo: MessagingRepository, uri: Uri): File? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val out = repo.media.newOutgoingFile("jpg")
        runCatching { ImageDownscaler.writeJpeg(context, uri, out, maxEdgePx = 1600) }.map { out }.getOrNull()
    }

private fun imageSize(f: File): Pair<Int?, Int?> {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, o)
    return (o.outWidth.takeIf { it > 0 }) to (o.outHeight.takeIf { it > 0 })
}

private suspend fun playLoopAnswer(a: LoopAnswerDto, repo: MessagingRepository, session: SessionManager, showImage: (File) -> Unit) {
    val media = a.media ?: return
    val f = repo.media.fetch(media) ?: return
    if (a.kind == "VOICE") session.voicePlayer.play("loop-${a.id}", f) else showImage(f)
}

// ---------------------------------------------------------------- pieces

@Composable
private fun ChatHeader(
    name: String,
    avatarUrl: String?,
    vibe: Vibe,
    isGroup: Boolean = false,
    subtitle: String?,
    subtitleActive: Boolean,
    onBack: () -> Unit,
    onTitle: () -> Unit,
    onCall: (() -> Unit)?,
    onHeartbeat: (() -> Unit)?,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    menu: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(ViroColors.surface).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = ViroColors.textPrimary) }
        Row(Modifier.weight(1f).clickable(onClick = onTitle), verticalAlignment = Alignment.CenterVertically) {
            Box {
                if (isGroup) GroupAvatar(name, 40.dp)
                else ViroAvatar(displayName = name, imageUrl = avatarUrl, size = ViroAvatarSize.Small)
                if (!isGroup) Box(Modifier.align(Alignment.BottomEnd).size(10.dp).clip(CircleShape).background(vibe.accent))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(name, color = ViroColors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, color = if (subtitleActive) vibe.accent else ViroColors.textSecondary, fontSize = 12.sp, maxLines = 1) }
            }
        }
        onHeartbeat?.let { IconButton(onClick = it) { Text("💓", fontSize = 20.sp) } }
        onCall?.let { IconButton(onClick = it) { Icon(Icons.Default.Call, "Call", tint = ViroColors.textPrimary) } }
        Box {
            IconButton(onClick = { onMenu(true) }) { Icon(Icons.Default.MoreVert, "More", tint = ViroColors.textPrimary) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) { menu() }
        }
    }
}

@Composable
private fun ChatMenu(
    isGroup: Boolean = false,
    isPrivate: Boolean,
    locked: Boolean,
    hidden: Boolean,
    muted: Boolean,
    disappearing: Int?,
    hasConversation: Boolean,
    hasPeer: Boolean,
    /** End-to-end encrypted: there is a safety number worth showing. */
    encrypted: Boolean,
    onPick: (String) -> Unit,
) {
    @Composable
    fun item(label: String, key: String, danger: Boolean = false, enabled: Boolean = true) = DropdownMenuItem(
        text = { Text(label, color = if (danger) ViroColors.consumerError else Color.Unspecified) },
        onClick = { onPick(key) },
        enabled = enabled,
    )
    if (isGroup) item("Group info", "groupinfo")
    if (encrypted) item("Encryption", "encryption")
    item("Search in chat", "search", enabled = hasConversation)
    if (hasPeer) item("Relationship & Moments", "relationship")
    if (hasPeer) item("Vibe", "vibe")
    if (hasPeer) item("Start a Loop", "loop")
    item("Starred messages", "starred", enabled = hasConversation)
    if (isPrivate) {
        item("End private session", "endprivate", danger = true)
        return
    }
    item(if (disappearing == null) "Disappearing messages" else "Disappearing: ${disappearingLabel(disappearing)}", "disappearing", enabled = hasConversation)
    if (hasPeer) item("Private session", "private")
    item(if (locked) "Unlock chat" else "Lock chat", "lock", enabled = hasConversation)
    item(if (hidden) "Unhide chat" else "Hide chat", "hide", enabled = hasConversation)
    item(if (muted) "Muted" else "Mute", "mute", enabled = hasConversation)
    item("Delete chat", "clear", enabled = hasConversation)
    if (!isGroup) item("Reset chat for both", "reset", danger = true, enabled = hasConversation)
    if (hasPeer) item("Erase & disconnect", "erase", danger = true)
}

@Composable
private fun ContextStrip(text: String, vibe: Vibe, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(vibe.accent.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = ViroColors.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null, tint = ViroColors.textSecondary)
    }
}

@Composable
private fun PinnedBar(m: ChatMessage, vibe: Vibe, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(ViroColors.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.PushPin, null, tint = vibe.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(previewOf(m), color = ViroColors.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyChat(name: String, isPrivate: Boolean, onViro: Boolean, vibe: Vibe) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (isPrivate) "🔒" else "👋", fontSize = 40.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                isPrivate -> "Private session with $name. Everything here is deleted for both of you when it ends."
                onViro -> "Say hello to $name. Hold the mic to send a voice note."
                else -> "$name isn't on Viro yet."
            },
            color = ViroColors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (onViro && !isPrivate) {
            Spacer(Modifier.height(8.dp))
            Text("Try a Loop from the menu — a question you both answer.", color = vibe.accent, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LockedChat(name: String, onUnlock: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    BackHandler { onBack() }
    Column(
        Modifier.fillMaxSize().background(ViroColors.background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Lock, null, tint = ViroColors.accent, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("Chat with $name is locked", color = ViroColors.textPrimary, fontSize = 18.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onUnlock) { Text("Unlock") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun CommitmentChip(s: CommitmentDetector.Suggestion, vibe: Vibe, onTap: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp)).background(vibe.accent.copy(alpha = 0.16f))
            .clickable(onClick = onTap).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📌", fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(s.chip, color = ViroColors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("“${s.action}” · ${s.whenLabel}", color = ViroColors.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Not now", tint = ViroColors.textSecondary) }
    }
}

@Composable
private fun CommitmentDialog(
    s: CommitmentDetector.Suggestion,
    peerName: String,
    vibe: Vibe,
    onDismiss: () -> Unit,
    onSave: (String, LocalDateTime) -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(s.action) }
    var due by remember { mutableStateOf(s.due) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (s.kind == CommitmentDetector.Kind.MEET) "Add to plans" else "Add commitment") },
        text = {
            Column {
                Text("Viro will remind you — $peerName won't be told.", color = ViroColors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = text, onValueChange = { text = it.take(200) }, label = { Text("What you'll do") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    android.app.DatePickerDialog(context, { _, y, m, d ->
                        android.app.TimePickerDialog(context, { _, h, min ->
                            due = LocalDateTime.of(y, m + 1, d, h, min)
                        }, due.hour, due.minute, true).show()
                    }, due.year, due.monthValue - 1, due.dayOfMonth).show()
                }) {
                    Icon(Icons.Default.Schedule, null)
                    Spacer(Modifier.width(6.dp))
                    Text(due.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim(), due) }, enabled = text.isNotBlank()) { Text("Save", color = vibe.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun MessageActionsSheet(
    msg: ChatMessage,
    vibe: Vibe,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onAction: (String) -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = ViroColors.surface) {
            Column(Modifier.padding(14.dp)) {
                if (!msg.deleted && !msg.isPending) {
                    if (!more) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            items(vibe.quickReactions) { e ->
                                Text(e, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).clickable { onReact(e) }.padding(4.dp))
                            }
                            item {
                                IconButton(onClick = { more = true }) { Icon(Icons.Default.Add, "More reactions", tint = ViroColors.textPrimary) }
                            }
                        }
                    } else {
                        LazyVerticalGrid(GridCells.Fixed(8), Modifier.heightIn(max = 240.dp)) {
                            items(ALL_REACTIONS) { e ->
                                Text(e, fontSize = 24.sp, modifier = Modifier.clickable { onReact(e) }.padding(4.dp))
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = ViroColors.divider)
                }
                @Composable
                fun row(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, key: String, color: Color = ViroColors.textPrimary) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onAction(key) }.padding(vertical = 11.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = color)
                        Spacer(Modifier.width(14.dp))
                        Text(label, color = color)
                    }
                }
                if (!msg.deleted && !msg.isPending) row(Icons.Default.Reply, "Reply", "reply")
                if (msg.type == "TEXT" && !msg.deleted) row(Icons.Default.ContentCopy, "Copy", "copy")
                if (msg.mine && msg.type == "TEXT" && !msg.deleted && !msg.isPending && System.currentTimeMillis() - msg.createdAt < 15 * 60_000) {
                    row(Icons.Default.Edit, "Edit", "edit")
                }
                if (!msg.deleted && !msg.isPending && !msg.viewOnce) row(Icons.Default.Shortcut, "Forward", "forward")
                if (!msg.deleted && !msg.isPending) {
                    row(Icons.Default.PushPin, if (pinned) "Unpin" else "Pin", "pin")
                    row(if (msg.starred) Icons.Default.StarOutline else Icons.Default.Star, if (msg.starred) "Unstar" else "Star", "star")
                }
                if (msg.type == "TEXT" && !msg.deleted) row(Icons.Default.NotificationsActive, "Remind me about this", "remind")
                if (!msg.isPending) row(Icons.Default.Info, "Info", "info")
                row(Icons.Default.Delete, "Delete", "delete", ViroColors.consumerError)
            }
        }
    }
}

@Composable
private fun VibeDialog(current: Vibe, onDismiss: () -> Unit, onPick: (Vibe) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vibe") },
        text = {
            Column {
                Text("Only you see which Vibe you chose.", color = ViroColors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Vibe.choosable.forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(v) }
                            .background(if (v == current) v.accent.copy(alpha = 0.18f) else Color.Transparent).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(v.mine))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(v.label, fontWeight = FontWeight.SemiBold)
                            Text(v.description, color = ViroColors.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    body: String?,
    options: List<Pair<String, Int?>>,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                body?.let { Text(it, color = ViroColors.textSecondary, fontSize = 13.sp); Spacer(Modifier.height(8.dp)) }
                options.forEach { (label, value) ->
                    Text(label, modifier = Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The safety number, and what it is for.
 *
 * The claim "end-to-end encrypted" is only worth anything if two people can
 * check it, so this shows the number both phones work out independently: if it
 * matches, no one is in the middle.
 */
@Composable
private fun EncryptionDialog(
    repo: MessagingRepository,
    peerUserId: String?,
    peerName: String,
    onDismiss: () -> Unit,
) {
    var number by remember { mutableStateOf<String?>(null) }
    var changed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(peerUserId) {
        val me = repo.myUserId()
        if (me != null && peerUserId != null) {
            number = repo.e2ee.safetyNumber(me, peerUserId)
            changed = repo.e2ee.identityChanged(peerUserId)
        }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("End-to-end encrypted") },
        text = {
            Column {
                Text(
                    "Messages in this chat are locked on this phone and opened on theirs. " +
                        "Viro's servers carry them without being able to read them.",
                    color = ViroColors.textSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                if (changed) {
                    Text(
                        "$peerName's security code changed. That happens when someone " +
                            "reinstalls Viro or changes phone — compare the number below to be sure.",
                        color = ViroColors.consumerError,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                when {
                    loading -> Text("Working it out…", color = ViroColors.textSecondary, fontSize = 13.sp)
                    number == null -> Text(
                        "The security number appears once you have exchanged a message.",
                        color = ViroColors.textSecondary,
                        fontSize = 13.sp,
                    )
                    else -> {
                        Text("Security number", color = ViroColors.textSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            number!!.chunked(20).joinToString("\n") { line -> line.chunked(5).joinToString("  ") },
                            fontSize = 16.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Compare it with $peerName in person or on a call. If both " +
                                "phones show the same number, no one else is in the middle.",
                            color = ViroColors.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                peerUserId?.let { id -> repo.acknowledgeIdentityChangeLater(id) }
                onDismiss()
            }) { Text("Done") }
        },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = ViroColors.consumerError) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StarredDialog(starred: List<ChatMessage>, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Starred messages") },
        text = {
            if (starred.isEmpty()) Text("Long-press a message and tap Star to keep it here.")
            else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(starred, key = { it.id }) { m ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(m.id) }.padding(vertical = 8.dp)) {
                        Text(previewOf(m), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(fullTime(m.createdAt), color = ViroColors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PhotoSendDialog(
    file: File,
    vibe: Vibe,
    viewOnce: Boolean,
    onToggleViewOnce: () -> Unit,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = ViroColors.surface) {
            Column(Modifier.padding(14.dp)) {
                AsyncImage(model = file, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = caption, onValueChange = { caption = it.take(1000) }, placeholder = { Text("Add a caption") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onToggleViewOnce) { Text(if (viewOnce) "① View once: on" else "View once: off", color = if (viewOnce) vibe.accent else ViroColors.textSecondary) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onSend(caption.trim()) }, colors = ButtonDefaults.buttonColors(containerColor = vibe.accent)) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun LoopVoiceDialog(recorder: VoiceRecorder, vibe: Vibe, onDismiss: () -> Unit, onDone: (VoiceRecorder.Recording) -> Unit) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    val elapsed by recorder.elapsedMs.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) recording = recorder.start()
    }
    AlertDialog(
        onDismissRequest = { recorder.cancel(); onDismiss() },
        title = { Text("Answer with your voice") },
        text = { Text(if (recording) "Recording… ${formatDuration(elapsed)}" else "Tap Record, speak, then Send.") },
        confirmButton = {
            if (!recording) {
                TextButton(onClick = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        recording = recorder.start()
                    } else permission.launch(android.Manifest.permission.RECORD_AUDIO)
                }) { Text("Record", color = vibe.accent) }
            } else {
                TextButton(onClick = {
                    recording = false
                    recorder.finish()?.let(onDone) ?: onDismiss()
                }) { Text("Send", color = vibe.accent) }
            }
        },
        dismissButton = { TextButton(onClick = { recorder.cancel(); onDismiss() }) { Text("Cancel") } },
    )
}

data class ForwardTarget(val conversationId: String?, val peerUserId: String?, val peerName: String)

@Composable
private fun ForwardDialog(session: SessionManager, onDismiss: () -> Unit, onPick: (ForwardTarget) -> Unit) {
    val conversations by session.messaging.conversations().collectAsState(initial = emptyList())
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(conversations) {
        names = conversations.mapNotNull { c -> c.peerUserId?.let { it to (session.contactsRepository.nameForUserId(it) ?: "Viro user") } }.toMap()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward to") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(conversations.filter { !it.isPrivate && !it.hidden }, key = { _, c -> c.id }) { _, c ->
                    val name = c.peerUserId?.let { names[it] } ?: "Conversation"
                    Text(name, modifier = Modifier.fillMaxWidth().clickable { onPick(ForwardTarget(c.id, c.peerUserId, name)) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks someone from this phone's contacts to share into the chat. */
@Composable
private fun ShareContactDialog(
    session: SessionManager,
    onDismiss: () -> Unit,
    onPick: (com.viroreach.app.messaging.ContactCard) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<com.viroreach.app.consumer.ContactListItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        contacts = runCatching { session.contactsRepository.loadCachedContacts() }.getOrDefault(emptyList())
    }
    val shown = remember(contacts, query) {
        val q = query.trim()
        contacts.asSequence()
            .filter { it.phoneE164 != null || it.userId != null }
            .filter { q.isBlank() || it.effectiveDisplayName.contains(q, true) || it.phoneE164?.contains(q) == true }
            .sortedBy { it.effectiveDisplayName.lowercase() }
            .take(200)
            .toList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share a contact") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search contacts") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    Text(
                        if (contacts.isEmpty()) "No contacts on this phone yet." else "No matches.",
                        color = ViroColors.textSecondary,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(shown, key = { it.id }) { c ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPick(
                                            com.viroreach.app.messaging.ContactCard(
                                                name = c.effectiveDisplayName,
                                                phones = listOfNotNull(c.phoneE164),
                                                viroId = null,
                                                userId = c.userId,
                                            ),
                                        )
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(c.effectiveDisplayName, color = ViroColors.textPrimary)
                                val line = c.phoneE164 ?: if (c.userId != null) "On Viro" else null
                                line?.let { Text(it, color = ViroColors.textSecondary, fontSize = 13.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun com.viroreach.app.messaging.ContactCard.toBody() =
    com.viroreach.core.network.ContactCardBody(name = name, phones = phones, viroId = viroId, userId = userId)

/** Send where I am now, or keep it moving for a while. */
@Composable
private fun ShareLocationDialog(
    onDismiss: () -> Unit,
    onSendOnce: () -> Unit,
    onShareLive: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share location") },
        text = {
            Column {
                Text(
                    "Send where you are now, or share live so it keeps up with you.",
                    color = ViroColors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Send my current location",
                    color = ViroColors.textPrimary,
                    modifier = Modifier.fillMaxWidth().clickable { onSendOnce() }.padding(vertical = 12.dp),
                )
                HorizontalDivider(color = ViroColors.surfaceRaised)
                Text(
                    "Share live for…",
                    color = ViroColors.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                com.viroreach.app.messaging.location.ViroLocation.LIVE_CHOICES.forEach { (seconds, label) ->
                    Text(
                        label,
                        color = ViroColors.textPrimary,
                        modifier = Modifier.fillMaxWidth().clickable { onShareLive(seconds) }.padding(vertical = 12.dp),
                    )
                }
                Text(
                    "While you share live, Viro shows a notification you can stop it from.",
                    color = ViroColors.textMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
