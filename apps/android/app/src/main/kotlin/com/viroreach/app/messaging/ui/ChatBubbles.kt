package com.viroreach.app.messaging.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.viroreach.app.messaging.ChatMessage
import com.viroreach.app.messaging.Delivery
import com.viroreach.app.messaging.MediaFiles
import com.viroreach.app.messaging.VoicePlayer
import com.viroreach.app.messaging.VoiceRecorder
import com.viroreach.core.designsystem.ViroColors
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val scheduledFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

fun clockOf(ms: Long): String = timeFmt.format(Date(ms))

data class BubbleCallbacks(
    val onLongPress: (ChatMessage) -> Unit,
    val onReply: (ChatMessage) -> Unit,
    val onRetry: (ChatMessage) -> Unit,
    val onToggleVoice: (ChatMessage, File) -> Unit,
    val onOpenImage: (ChatMessage, File) -> Unit,
    val onOpenViewOnce: (ChatMessage) -> Unit,
    val onReactionTap: (ChatMessage) -> Unit,
    val onOpenLoop: (String) -> Unit,
    val onJumpTo: (String) -> Unit,
    val onVote: (ChatMessage, List<Int>) -> Unit = { _, _ -> },
    val onTranscribe: (ChatMessage) -> Unit = {},
    /** Opens a document with whatever app on the phone handles it. */
    val onOpenFile: (ChatMessage) -> Unit = {},
    val onOpenPlace: (com.viroreach.app.messaging.SharedPlace) -> Unit = {},
    val onStopSharingLocation: (ChatMessage) -> Unit = {},
    val onMessageContact: (com.viroreach.app.messaging.ContactCard) -> Unit = {},
    val onCallContact: (com.viroreach.app.messaging.ContactCard) -> Unit = {},
    val onSaveContact: (com.viroreach.app.messaging.ContactCard) -> Unit = {},
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    msg: ChatMessage,
    vibe: Vibe,
    senderLabel: (String) -> String,
    delivery: Delivery,
    media: MediaFiles,
    player: VoicePlayer.State,
    highlighted: Boolean,
    playEffect: Boolean,
    callbacks: BubbleCallbacks,
    /** In a group: the sender's name above their incoming messages. */
    groupSender: String? = null,
    transcriptsEnabled: Boolean = false,
) {
    if (msg.type == "SYSTEM") {
        SystemRow(msg, senderLabel)
        return
    }
    if (msg.type == "LOOP") {
        LoopEventRow(msg, vibe, senderLabel, callbacks.onOpenLoop)
        return
    }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val drag = remember(msg.id) { Animatable(0f) }
    val align = if (msg.mine) Alignment.End else Alignment.Start
    val bare = msg.type == "STICKER" && !msg.deleted
    val bubbleColor = when {
        msg.deleted || bare -> Color.Transparent
        msg.mine -> vibe.mine
        else -> vibe.theirs
    }
    val shape = RoundedCornerShape(
        topStart = vibe.corner,
        topEnd = vibe.corner,
        bottomStart = if (msg.mine) vibe.corner else 4.dp,
        bottomEnd = if (msg.mine) 4.dp else vibe.corner,
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (highlighted) vibe.accent.copy(alpha = 0.16f) else Color.Transparent)
            .padding(vertical = 2.dp),
        horizontalAlignment = align,
    ) {
        Box {
            if (playEffect && msg.effect != null) EffectOverlay(msg.effect!!, vibe)
            Column(
                Modifier
                    .offset { IntOffset(drag.value.roundToInt(), 0) }
                    // Swipe right to reply, as in WhatsApp.
                    .pointerInput(msg.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (drag.value > 90f && !msg.deleted) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    callbacks.onReply(msg)
                                }
                                scope.launch { drag.animateTo(0f, tween(180)) }
                            },
                            onDragCancel = { scope.launch { drag.animateTo(0f) } },
                        ) { _, amount ->
                            scope.launch { drag.snapTo((drag.value + amount).coerceIn(0f, 160f)) }
                        }
                    }
                    .widthIn(max = 300.dp)
                    .clip(shape)
                    .background(bubbleColor)
                    .then(
                        // A sealed message still being opened sits quietly
                        // in its place, the way a deleted one does, rather
                        // than as a full bubble shouting about itself.
                        if (msg.deleted || msg.type == "ENCRYPTED") {
                            Modifier.background(ViroColors.surface.copy(alpha = 0.5f), shape)
                        } else {
                            Modifier
                        },
                    )
                    .combinedClickable(
                        onClick = {
                            when {
                                msg.outboxStatus == "FAILED" -> callbacks.onRetry(msg)
                                else -> Unit
                            }
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            callbacks.onLongPress(msg)
                        },
                    )
                    .padding(horizontal = if (bare) 2.dp else 12.dp, vertical = if (bare) 2.dp else 8.dp),
            ) {
                if (groupSender != null && !msg.mine && !msg.deleted) {
                    Text(groupSender, color = memberColor(msg.senderUserId), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                if (msg.forwarded && !msg.deleted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shortcut, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Forwarded", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontStyle = FontStyle.Italic)
                    }
                }
                msg.replyTo?.takeIf { !msg.deleted }?.let { reply ->
                    ReplyQuote(
                        author = senderLabel(reply.senderUserId ?: ""),
                        text = when {
                            reply.deleted == true -> "Deleted message"
                            reply.type == "VOICE" -> "🎤 Voice message"
                            reply.type == "IMAGE" -> "📷 Photo"
                            else -> reply.body ?: ""
                        },
                        accent = if (msg.mine) Color.White else vibe.accent,
                        onClick = { callbacks.onJumpTo(reply.id) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                when {
                    msg.deleted -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Block, null, tint = ViroColors.textSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (msg.mine) "You deleted this message" else "This message was deleted",
                            color = ViroColors.textSecondary,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                    msg.viewOnce -> ViewOnceContent(msg, onOpen = { callbacks.onOpenViewOnce(msg) })
                    msg.type == "VOICE" -> {
                        VoiceContent(msg, media, player, vibe, callbacks.onToggleVoice)
                        // An encrypted voice note is noise to the server, so
                        // there is nothing to offer: no transcript line.
                        TranscriptLine(msg, transcriptsEnabled && msg.media?.sealedKey == null, callbacks.onTranscribe)
                    }
                    msg.type == "IMAGE" -> ImageContent(msg, media, callbacks.onOpenImage)
                    msg.type == "POLL" -> PollContent(msg, vibe, senderLabel, callbacks.onVote)
                    msg.type == "GIF" -> GifContent(msg)
                    msg.type == "STICKER" -> StickerContent(msg)
                    msg.type == "FILE" -> FileContent(msg, downloading = false, onOpen = callbacks.onOpenFile)
                    msg.type == "LOCATION" -> msg.place?.let { place ->
                        LocationContent(
                            msg = msg,
                            place = place,
                            onOpen = callbacks.onOpenPlace,
                            onStopSharing = callbacks.onStopSharingLocation,
                        )
                    } ?: Text("Location", color = Color.White, fontSize = 16.sp)
                    // Sealed and not opened yet. Almost always this is a moment
                    // while the retry queue gets to it, and it is replaced in
                    // place when it opens; only a message this phone can never
                    // open (sealed before it was signed in) says so.
                    msg.type == "ENCRYPTED" -> Text(
                        when {
                            msg.isUnavailable -> "Not available on this phone"
                            msg.isResending -> "Getting this message from their phone…"
                            else -> "Decrypting message…"
                        },
                        color = ViroColors.textSecondary,
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                    )
                    msg.type == "CONTACT" -> msg.contactCard?.let { card ->
                        ContactContent(
                            card = card,
                            onMessage = callbacks.onMessageContact,
                            onCall = callbacks.onCallContact,
                            onSave = callbacks.onSaveContact,
                        )
                    } ?: Text("Shared contact", color = Color.White, fontSize = 16.sp)
                    else -> {
                        Text(withMentionsHighlighted(msg.body.orEmpty(), vibe.accent), color = Color.White, fontSize = 16.sp)
                        msg.linkPreview?.let { LinkPreviewCard(it, media, msg.mine) }
                    }
                }
                MetaLine(msg, delivery)
            }
        }
        if (msg.reactions.isNotEmpty() && !msg.deleted) {
            ReactionsRow(msg, onClick = { callbacks.onReactionTap(msg) })
        }
        if (msg.outboxStatus == "FAILED") {
            val why = ((msg.metadata["outbox"] as? Map<*, *>)?.get("error") as? String)?.takeIf { it.isNotBlank() }
            Text(
                if (why != null) "Not sent — ${why.trimEnd('.')}. Tap to retry." else "Not sent. Tap to retry.",
                color = ViroColors.consumerError,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).clickable { callbacks.onRetry(msg) },
            )
        }
    }
}

@Composable
private fun ColumnScope.MetaLine(msg: ChatMessage, delivery: Delivery) {
    Row(
        Modifier.padding(top = 3.dp).align(Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (msg.starred) Icon(Icons.Default.Star, "Starred", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
        if (msg.expiresAt != null) Icon(Icons.Default.Timer, "Disappearing", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
        if (msg.editedAt != null && !msg.deleted) Text("edited", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        if (msg.deliverAt != null && delivery == Delivery.SCHEDULED) {
            Icon(Icons.Default.Schedule, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(12.dp))
            Text("Scheduled ${scheduledFmt.format(Date(msg.deliverAt!!))}", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
        } else {
            Text(clockOf(msg.createdAt), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
        if (msg.mine && !msg.deleted) Ticks(delivery)
    }
}

/** ✓ sent · ✓✓ delivered · blue ✓✓ read · clock while sending. */
@Composable
fun Ticks(delivery: Delivery, tint: Color = Color.White.copy(alpha = 0.75f)) {
    when (delivery) {
        Delivery.SENDING -> Icon(Icons.Default.Schedule, "Sending", tint = tint, modifier = Modifier.size(13.dp))
        Delivery.FAILED -> Icon(Icons.Default.ErrorOutline, "Not sent", tint = ViroColors.consumerError, modifier = Modifier.size(14.dp))
        Delivery.SENT -> Icon(Icons.Default.Done, "Sent", tint = tint, modifier = Modifier.size(15.dp))
        Delivery.DELIVERED -> Icon(Icons.Default.DoneAll, "Delivered", tint = tint, modifier = Modifier.size(15.dp))
        Delivery.READ -> Icon(Icons.Default.DoneAll, "Read", tint = Color(0xFF6EE7FF), modifier = Modifier.size(15.dp))
        Delivery.SCHEDULED, Delivery.INCOMING -> Unit
    }
}

@Composable
fun ReplyQuote(author: String, text: String, accent: Color, onClick: () -> Unit = {}) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .clickable(onClick = onClick)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(author, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ReactionsRow(msg: ChatMessage, onClick: () -> Unit) {
    val grouped = msg.reactions.groupBy { it.emoji }
    Row(
        Modifier
            .padding(horizontal = 8.dp)
            .offset(y = (-4).dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ViroColors.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        grouped.forEach { (emoji, list) ->
            Text(if (list.size > 1) "$emoji ${list.size}" else emoji, fontSize = 13.sp, color = Color.White)
        }
    }
}

@Composable
private fun ViewOnceContent(msg: ChatMessage, onOpen: () -> Unit) {
    val kind = if (msg.type == "VOICE") "Voice message" else "Photo"
    val opened = msg.viewed || (msg.media == null && !msg.isPending)
    Row(
        Modifier.clickable(enabled = !msg.mine && !opened, onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(Color.White.copy(alpha = if (opened) 0.2f else 0.9f)),
            contentAlignment = Alignment.Center,
        ) { Text("1", color = if (opened) Color.White else Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                msg.mine && msg.viewed -> "Opened"
                msg.mine -> "$kind · view once"
                opened -> "Opened"
                else -> "$kind · tap to open once"
            },
            color = Color.White,
        )
    }
}

@Composable
private fun rememberMediaFile(msg: ChatMessage, media: MediaFiles): File? {
    val local = msg.localMediaPath?.let { File(it) }?.takeIf { it.exists() }
    val state = produceState(initialValue = local, msg.id, msg.media?.id) {
        if (value == null) msg.media?.let { value = media.fetch(it) }
    }
    return state.value
}

/**
 * A photo, held back when the data rule says so. Already downloaded or already
 * on this phone, it shows regardless; otherwise it waits to be tapped.
 */
@Composable
private fun rememberPhoto(msg: ChatMessage, media: MediaFiles): Pair<File?, (() -> Unit)?> {
    val context = LocalContext.current
    val settings = remember(context) { com.viroreach.app.messaging.MediaSettings(context) }
    val prefs by settings.preferences.collectAsState(initial = com.viroreach.app.messaging.MediaPreferences())
    var wanted by remember(msg.id, msg.media?.id) { mutableStateOf(false) }
    val local = msg.localMediaPath?.let { File(it) }?.takeIf { it.exists() }
    val cached = remember(msg.media?.id, local) { local ?: msg.media?.let { media.cachedFile(it) } }
    val allowed = remember(prefs, msg.id) {
        com.viroreach.app.messaging.shouldAutoDownload(
            com.viroreach.app.messaging.AutoKind.PHOTO,
            settings.onMeteredConnection(),
            prefs,
        )
    }
    val state = produceState(initialValue = cached, msg.id, msg.media?.id, allowed, wanted) {
        if (value == null && (allowed || wanted)) msg.media?.let { value = media.fetch(it) }
    }
    return state.value to if (state.value == null && !allowed) ({ wanted = true }) else null
}

@Composable
private fun VoiceContent(
    msg: ChatMessage,
    media: MediaFiles,
    player: VoicePlayer.State,
    vibe: Vibe,
    onToggle: (ChatMessage, File) -> Unit,
) {
    val file = rememberMediaFile(msg, media)
    val isThis = player.messageId == msg.id
    val duration = msg.media?.durationMs ?: 0L
    val progress = if (isThis && player.durationMs > 0) player.positionMs.toFloat() / player.durationMs else 0f
    val bars = remember(msg.media?.waveform) { VoiceRecorder.decodeWaveform(msg.media?.waveform) }
    Row(Modifier.widthIn(min = 220.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { file?.let { onToggle(msg, it) } },
            enabled = file != null,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
        ) {
            when {
                file == null -> CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                isThis && player.playing -> Icon(Icons.Default.Pause, "Pause", tint = Color.White)
                else -> Icon(Icons.Default.PlayArrow, "Play", tint = Color.White)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Waveform(bars, progress, played = Color.White, idle = Color.White.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth().height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDuration(if (isThis) player.positionMs else duration),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                )
                if (isThis) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${if (player.speed % 1f == 0f) player.speed.toInt() else player.speed}×",
                        color = vibe.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
fun Waveform(bars: List<Float>, progress: Float, played: Color, idle: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = bars.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        bars.forEachIndexed { i, v ->
            val h = (size.height * v).coerceAtLeast(3.dp.toPx())
            val x = i * (w + gap)
            drawRoundRect(
                color = if (i.toFloat() / n < progress) played else idle,
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun ImageContent(msg: ChatMessage, media: MediaFiles, onOpen: (ChatMessage, File) -> Unit) {
    val (file, download) = rememberPhoto(msg, media)
    val ratio = msg.media?.let { m ->
        if ((m.width ?: 0) > 0 && (m.height ?: 0) > 0) m.width!!.toFloat() / m.height!! else null
    } ?: 1f
    Column {
        Box(
            Modifier
                .width(240.dp)
                .aspectRatio(ratio.coerceIn(0.6f, 1.8f))
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable(enabled = file != null || download != null) {
                    if (file != null) onOpen(msg, file) else download?.invoke()
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                file != null -> AsyncImage(model = file, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                download != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, null, tint = Color.White)
                    Text("Tap to download", color = Color.White, fontSize = 13.sp)
                    msg.media?.sizeBytes?.let {
                        Text(formatFileSize(it), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
                else -> CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            }
        }
        msg.body?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color.White, fontSize = 15.sp)
        }
    }
}

@Composable
private fun SystemRow(msg: ChatMessage, senderLabel: (String) -> String) {
    val who = if (msg.mine) "You" else senderLabel(msg.senderUserId)
    val text = when (msg.event) {
        "private_started" -> "$who started a private session. It will be deleted for both of you when it ends."
        else -> "$who ${msg.body.orEmpty()}"
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = ViroColors.textSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ViroColors.surface.copy(alpha = 0.8f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun LoopEventRow(msg: ChatMessage, vibe: Vibe, senderLabel: (String) -> String, onOpen: (String) -> Unit) {
    val loopId = msg.loopId ?: return
    val completed = msg.event == "loop_completed"
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(vibe.accent.copy(alpha = 0.16f))
                .clickable { onOpen(loopId) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔁", fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(msg.body ?: "Loop", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    if (completed) "Completed together · tap to see both answers"
                    else "${if (msg.mine) "You" else senderLabel(msg.senderUserId)} answered · tap to see",
                    color = ViroColors.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            color = ViroColors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ViroColors.surface)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Confetti or a heartbeat pulse, played once when the message first appears. */
@Composable
private fun EffectOverlay(effect: String, vibe: Vibe) {
    when (effect) {
        "heartbeat" -> {
            val t = rememberInfiniteTransition(label = "beat")
            val s by t.animateFloat(1f, 1.35f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
            var visible by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2600)
                visible = false
            }
            if (visible) Text("💓", fontSize = 40.sp, modifier = Modifier.scale(s).offset(x = (-24).dp, y = (-24).dp))
        }
        "confetti" -> {
            val progress = remember { Animatable(0f) }
            LaunchedEffect(Unit) { progress.animateTo(1f, tween(1800, easing = LinearEasing)) }
            val pieces = remember {
                List(28) { Triple(Random.nextFloat(), Random.nextFloat() * 0.6f + 0.4f, listOf(0xFFFF6F91, 0xFFFFD166, 0xFF6EE7FF, 0xFFA28BFF, 0xFF7CFFB2)[it % 5]) }
            }
            if (progress.value < 1f) {
                Canvas(Modifier.size(300.dp, 160.dp).alpha(1f - progress.value)) {
                    pieces.forEach { (x, speed, color) ->
                        val y = size.height * progress.value * speed * 1.4f
                        drawRect(Color(color), topLeft = Offset(size.width * x, y - 40f), size = Size(8f, 14f))
                    }
                }
            }
        }
    }
}

@Composable
fun TypingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "typing")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a = if (phase.toInt() == i) 1f else 0.35f
            Box(Modifier.size(5.dp).clip(CircleShape).background(color.copy(alpha = a)))
        }
    }
}

@Composable
fun FullImage(file: File, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(model = file, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
            Icon(Icons.Default.Close, "Close", tint = Color.White)
        }
    }
}
