package com.viroreach.app.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val types = listOf("FREE" to "Free", "BREAK" to "Break", "LISTENING" to "Listening",
    "WATCHING" to "Watching", "GAMING" to "Gaming", "WORKING" to "Working", "CUSTOM" to "Custom")

/** The same small header sits inside the existing conversation list. No new
 * bottom destination, socket, chat implementation or call engine. */
@Composable
fun MomentsHeader(session: SessionManager, onCall: (String?, String?, String) -> Unit) {
    // Consumer surfaces use Viro's navy palette in both app theme modes. Keep
    // Material controls legible on those surfaces when the system uses light mode.
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
    )) {
        MomentsContent(session, onCall)
    }
}

@Composable
private fun MomentsContent(session: SessionManager, onCall: (String?, String?, String) -> Unit) {
    val repo = session.moments
    val moments by repo.moments.collectAsState()
    val loaded by repo.loaded.collectAsState()
    val error by repo.error.collectAsState()
    val profile by session.profileRepository.profile.collectAsState(initial = UserProfile())
    val scope = rememberCoroutineScope()
    var clock by remember { mutableLongStateOf(repo.now()) }
    var create by rememberSaveable { mutableStateOf(false) }
    var all by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repo) {
        repo.refresh()
        var ticks = 0
        while (true) {
            delay(1000)
            repo.prune()
            clock = repo.now()
            if (++ticks % 30 == 0) repo.refresh()
        }
    }
    val own = moments.firstOrNull { it.creatorUserId == session.tokenStore.getUserId() }
    val peers = moments.filter { it.creatorUserId != session.tokenStore.getUserId() }
    fun open(m: MomentDto) { actionError = null; selected = m.id }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = ViroColors.surfaceRaised,
            modifier = Modifier.fillMaxWidth().clickable { if (own == null) create = true else open(own) }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ViroAvatar(size = ViroAvatarSize.Small, imageUrl = profile.effectivePhotoUrl, displayName = profile.displayName)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (own == null) "What are you up to?" else "Your Moment", color = ViroColors.textPrimary,
                        fontWeight = FontWeight.SemiBold)
                    Text(own?.let { "${it.activity()} · ${remainingMinutes(it.endsAt(), clock)} min" } ?: "Start a Moment",
                        color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (own != null) Text("Manage", color = ViroColors.textMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("NOW", color = ViroColors.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { all = true }) { Text("See all", color = ViroColors.textMuted) }
        }
        if (peers.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cardWidth = (maxWidth / 2.65f).coerceAtLeast(104.dp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(peers.take(4), key = { it.id }) { m ->
                        Surface(shape = RoundedCornerShape(12.dp), color = ViroColors.surface,
                            modifier = Modifier.width(cardWidth).clickable { open(m) }) {
                            Column(Modifier.padding(10.dp)) {
                                MomentAvatar(m)
                                Spacer(Modifier.height(6.dp))
                                Text(m.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = ViroColors.textPrimary, fontWeight = FontWeight.SemiBold)
                                Text(m.activity(), maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall)
                                Text("${remainingMinutes(m.endsAt(), clock)} min", color = ViroColors.textMuted,
                                    style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = { open(m) }, contentPadding = PaddingValues(0.dp)) {
                                    Text("Open", color = ViroColors.textMuted)
                                }
                            }
                        }
                    }
                }
            }
        } else if (loaded) {
            Text("It's quiet right now.", color = ViroColors.textPrimary, fontWeight = FontWeight.Medium)
            Text("Start a Moment and give your people something to join.", color = ViroColors.textMuted,
                style = MaterialTheme.typography.bodySmall)
            if (own == null) TextButton(onClick = { create = true }) { Text("Start a Moment", color = ViroColors.textMuted) }
        } else {
            Text("Checking what's happening…", color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (error != null) TextButton(onClick = { scope.launch { repo.refresh() } }) {
            Text("Couldn't refresh · Retry", color = ViroColors.textMuted)
        }
        Text("CONVERSATIONS", color = ViroColors.textPrimary, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
    }

    if (create) CreateMomentSheet(onDismiss = { create = false }, onStart = { body ->
        busy = true
        scope.launch {
            repo.create(body).onSuccess { create = false; open(it) }.onFailure { actionError = it.message }
            busy = false
        }
    }, busy = busy, error = actionError)

    if (all && selected == null) MomentPage("Moments", onBack = { all = false }) {
        item(key = "mine") {
            Text("Your Moment", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleMedium)
            if (own != null) MomentRow(own, clock) { open(own) }
            else TextButton(onClick = { create = true }) { Text("Start a Moment") }
            Text("Active now", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 16.dp))
            if (peers.isEmpty()) Text("It's quiet right now.", color = ViroColors.textMuted)
        }
        items(peers, key = { it.id }) { m -> MomentRow(m, clock) { open(m) } }
    }

    selected?.let { id ->
        val moment = moments.firstOrNull { it.id == id }
        LaunchedEffect(id) { repo.refresh() }
        MomentPage("Moment", onBack = { selected = null; actionError = null }) {
            item(key = "detail") {
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
                    Text("Visible to ${if (moment.visibility == "CONTACTS") "the host's contacts" else "Viro connections"}",
                        color = ViroColors.textMuted)
                    Text("Started ${java.time.Duration.between(java.time.Instant.parse(moment.createdAt), java.time.Instant.ofEpochMilli(clock)).toMinutes().coerceAtLeast(0)} min ago",
                        color = ViroColors.textMuted, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(24.dp))
                    if (moment.creatorUserId == session.tokenStore.getUserId()) {
                        OutlinedButton(enabled = !busy && moment.endsAt() < java.time.Instant.parse(moment.createdAt).toEpochMilli() + 7_200_000L,
                            onClick = { busy = true; scope.launch {
                                repo.extend(id).onFailure { actionError = it.message }; busy = false
                            } }, modifier = Modifier.fillMaxWidth()) { Text("Extend 15 min") }
                        Button(enabled = !busy, onClick = { busy = true; scope.launch {
                            repo.end(id).onSuccess { selected = null }.onFailure { actionError = it.message }; busy = false
                        } }, modifier = Modifier.fillMaxWidth()) { Text("End Moment") }
                    } else if (moment.allowVoice) {
                        Button(enabled = !busy, onClick = { busy = true; scope.launch {
                            repo.verify(id).onSuccess { fresh ->
                                selected = null; all = false; onCall(fresh.creatorUserId, null, fresh.displayName)
                            }.onFailure { actionError = it.message }; busy = false
                        } }, modifier = Modifier.fillMaxWidth()) { Text("Talk") }
                    }
                    if (actionError != null) Text(actionError!!, color = ViroColors.textMuted, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun MomentAvatar(m: MomentDto) {
    Box(Modifier.border(1.dp, ViroColors.BlueAccent, CircleShape).padding(3.dp)) {
        ViroAvatar(size = ViroAvatarSize.Small, imageUrl = m.avatarUrl, displayName = m.displayName)
    }
}

@Composable
private fun MomentRow(m: MomentDto, now: Long, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        MomentAvatar(m)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(m.displayName, color = ViroColors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(m.activity(), color = ViroColors.textMuted)
            Text("${remainingMinutes(m.endsAt(), now)} min", color = ViroColors.textMuted, style = MaterialTheme.typography.bodySmall)
        }
        Text("Open", color = ViroColors.textMuted)
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
