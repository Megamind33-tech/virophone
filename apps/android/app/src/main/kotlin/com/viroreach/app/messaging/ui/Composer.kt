package com.viroreach.app.messaging.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.viroreach.app.messaging.ChatMessage
import com.viroreach.app.messaging.VoiceRecorder
import com.viroreach.core.designsystem.ViroColors
import java.util.Calendar

sealed class ComposerAction {
    data class Text(val body: String, val deliverAt: Long?, val effect: String?) : ComposerAction()
    data class Edit(val messageId: String, val body: String) : ComposerAction()
    data class Voice(val recording: VoiceRecorder.Recording, val viewOnce: Boolean) : ComposerAction()
    object PickPhoto : ComposerAction()
    object TakePhoto : ComposerAction()
    object CreatePoll : ComposerAction()
    object OpenStickers : ComposerAction()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Composer(
    vibe: Vibe,
    recorder: VoiceRecorder,
    replyTo: ChatMessage?,
    replyAuthor: String,
    editing: ChatMessage?,
    enabled: Boolean,
    disabledReason: String?,
    viewOnce: Boolean,
    onToggleViewOnce: () -> Unit,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    onTyping: (String) -> Unit,
    onAction: (ComposerAction) -> Unit,
    /** Every change to the draft, for link previews. */
    onDraftChanged: (String) -> Unit = {},
    /** Shown above the input: the link preview that will go with the message. */
    aboveInput: (@Composable () -> Unit)? = null,
    stickersOpen: Boolean = false,
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val elapsed by recorder.elapsedMs.collectAsState()
    val level by recorder.level.collectAsState()
    val density = LocalDensity.current
    val cancelPx = with(density) { 110.dp.toPx() }
    val lockPx = with(density) { 90.dp.toPx() }

    LaunchedEffect(editing?.id) { if (editing != null) draft = editing.body.orEmpty() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startRecording(): Boolean {
        if (!hasMic()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        val ok = recorder.start()
        if (ok) {
            recording = true
            onTyping("recording")
        }
        return ok
    }

    fun finishRecording(send: Boolean) {
        recording = false
        locked = false
        dragX = 0f
        onTyping("idle")
        if (!send) {
            recorder.cancel()
            return
        }
        recorder.finish()?.let { onAction(ComposerAction.Voice(it, viewOnce)) }
    }

    fun sendText(deliverAt: Long? = null, effect: String? = null) {
        val body = draft.trim()
        if (body.isEmpty()) return
        if (editing != null) onAction(ComposerAction.Edit(editing.id, body))
        else onAction(ComposerAction.Text(body, deliverAt, effect))
        draft = ""
        onDraftChanged("")
        onTyping("idle")
    }

    fun pickSendLater() {
        val now = Calendar.getInstance()
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, min ->
                val at = Calendar.getInstance().apply { set(y, m, d, h, min, 0) }.timeInMillis
                if (at > System.currentTimeMillis() + 60_000) sendText(deliverAt = at)
            }, now.get(Calendar.HOUR_OF_DAY), (now.get(Calendar.MINUTE) / 5 + 1) * 5 % 60, true).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = now.timeInMillis - 1000
        }.show()
    }

    Column(Modifier.fillMaxWidth().background(ViroColors.NavyBackground.copy(alpha = 0.92f))) {
        if (replyTo != null && editing == null) {
            ContextBar(icon = Icons.Default.Reply, title = "Replying to $replyAuthor", body = previewOf(replyTo), accent = vibe.accent, onClose = onCancelReply)
        }
        if (editing != null) {
            ContextBar(icon = Icons.Default.Edit, title = "Editing message", body = editing.body.orEmpty(), accent = vibe.accent, onClose = {
                draft = ""
                onCancelEdit()
            })
        }
        if (!enabled) {
            Text(
                disabledReason ?: "You can't message here.",
                color = ViroColors.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            return@Column
        }
        aboveInput?.invoke()
        if (recording) {
            RecordingBar(
                vibe = vibe,
                elapsedMs = elapsed,
                level = level,
                locked = locked,
                dragX = dragX,
                viewOnce = viewOnce,
                onToggleViewOnce = onToggleViewOnce,
                onCancel = { finishRecording(send = false) },
                onSend = { finishRecording(send = true) },
            )
            if (locked) return@Column
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!recording) {
                Box {
                    IconButton(onClick = { attachOpen = true }) {
                        Icon(Icons.Default.AddCircleOutline, "Attach", tint = vibe.accent)
                    }
                    DropdownMenu(expanded = attachOpen, onDismissRequest = { attachOpen = false }) {
                        DropdownMenuItem(text = { Text("Photo") }, leadingIcon = { Icon(Icons.Default.Photo, null) }, onClick = {
                            attachOpen = false
                            onAction(ComposerAction.PickPhoto)
                        })
                        DropdownMenuItem(text = { Text("Camera") }, leadingIcon = { Icon(Icons.Default.PhotoCamera, null) }, onClick = {
                            attachOpen = false
                            onAction(ComposerAction.TakePhoto)
                        })
                        DropdownMenuItem(text = { Text("Poll") }, leadingIcon = { Icon(Icons.Default.Poll, null) }, onClick = {
                            attachOpen = false
                            onAction(ComposerAction.CreatePoll)
                        })
                        DropdownMenuItem(text = { Text("Stickers & GIFs") }, leadingIcon = { Icon(Icons.Default.EmojiEmotions, null) }, onClick = {
                            attachOpen = false
                            onAction(ComposerAction.OpenStickers)
                        })
                        DropdownMenuItem(
                            text = { Text(if (viewOnce) "View once: on" else "View once: off") },
                            leadingIcon = { Icon(Icons.Default.LooksOne, null) },
                            onClick = {
                                attachOpen = false
                                onToggleViewOnce()
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        onTyping(if (it.isBlank()) "idle" else "typing")
                        onDraftChanged(it)
                    },
                    trailingIcon = {
                        IconButton(onClick = { onAction(ComposerAction.OpenStickers) }) {
                            Icon(
                                Icons.Default.EmojiEmotions,
                                "Stickers and GIFs",
                                tint = if (stickersOpen) vibe.accent else ViroColors.MutedBlue,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 140.dp),
                    placeholder = { Text("Message", color = ViroColors.MutedBlue) },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ViroColors.NavySurfaceElevated,
                        unfocusedContainerColor = ViroColors.NavySurfaceElevated,
                        focusedBorderColor = vibe.accent.copy(alpha = 0.6f),
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = vibe.accent,
                    ),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(6.dp))
            if (draft.isNotBlank() || editing != null) {
                Box {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(vibe.accent)
                            .combinedClickable(
                                onClick = { sendText() },
                                onLongClick = { if (editing == null) menuOpen = true },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (editing != null) Icons.Default.Check else Icons.Default.Send, if (editing != null) "Save edit" else "Send", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Send later…") }, leadingIcon = { Icon(Icons.Default.Schedule, null) }, onClick = {
                            menuOpen = false
                            pickSendLater()
                        })
                        if (vibe.effects) {
                            DropdownMenuItem(text = { Text("Send with confetti 🎉") }, onClick = {
                                menuOpen = false
                                sendText(effect = "confetti")
                            })
                        }
                        if (vibe.heartbeat) {
                            DropdownMenuItem(text = { Text("Send with a heartbeat 💓") }, onClick = {
                                menuOpen = false
                                sendText(effect = "heartbeat")
                            })
                        }
                    }
                }
            } else {
                // Hold to record, slide left to cancel, slide up to lock.
                val size = if (vibe.voiceFirst) 58.dp else 48.dp
                Box(
                    Modifier
                        .size(size)
                        .scale(if (recording) 1.25f else 1f)
                        .clip(CircleShape)
                        .background(if (recording) ViroColors.consumerError else vibe.accent)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (!startRecording()) return@awaitEachGesture
                                var outcome = "send"
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    dragX = dx.coerceAtMost(0f)
                                    if (dx < -cancelPx) { outcome = "cancel"; break }
                                    if (dy < -lockPx) { outcome = "lock"; break }
                                    if (!change.pressed) break
                                }
                                when (outcome) {
                                    "cancel" -> finishRecording(send = false)
                                    "lock" -> { locked = true; dragX = 0f }
                                    else -> finishRecording(send = true)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Mic, "Hold to record a voice message", tint = Color.White, modifier = Modifier.size(if (vibe.voiceFirst) 28.dp else 24.dp))
                }
            }
        }
    }
}

@Composable
private fun RecordingBar(
    vibe: Vibe,
    elapsedMs: Long,
    level: Float,
    locked: Boolean,
    dragX: Float,
    viewOnce: Boolean,
    onToggleViewOnce: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size((10 + level * 10).dp).clip(CircleShape).background(ViroColors.consumerError))
        Spacer(Modifier.width(10.dp))
        Text(formatDuration(elapsedMs), color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        if (locked) {
            TextButton(onClick = onToggleViewOnce) {
                Text(if (viewOnce) "① View once" else "View once", color = if (viewOnce) vibe.accent else ViroColors.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCancel) { Icon(Icons.Default.Delete, "Discard", tint = ViroColors.textSecondary) }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(vibe.accent).clickable(onClick = onSend),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Send, "Send voice message", tint = Color.White) }
        } else {
            Text(
                if (dragX < -40f) "Release to cancel" else "◀ Slide to cancel · ▲ lock",
                color = ViroColors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.offset(x = with(LocalDensity.current) { (dragX / 3).toDp() }),
            )
        }
    }
}

@Composable
private fun ContextBar(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, accent: Color, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(ViroColors.NavySurface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = ViroColors.textSecondary, fontSize = 13.sp, maxLines = 1)
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cancel", tint = ViroColors.textSecondary) }
    }
}

fun previewOf(m: ChatMessage): String = when {
    m.deleted -> "Deleted message"
    m.type == "VOICE" -> "🎤 Voice message"
    m.type == "IMAGE" -> if (m.body.isNullOrBlank()) "📷 Photo" else "📷 ${m.body}"
    else -> m.body.orEmpty()
}
