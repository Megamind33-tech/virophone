package com.viroreach.app.moments.engine

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.app.moments.MomentRoomState
import com.viroreach.app.moments.MomentTouch
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.network.MomentChoiceBody
import com.viroreach.core.network.MomentParticipantDto
import com.viroreach.core.network.MomentRoomChangeBody
import com.viroreach.core.network.MomentTimerBody
import com.viroreach.core.network.MomentTimerDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ feel

/** How each touch feels in the hand: a hug is long and soft, a tap is a tick. */
object Haptics {
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun feel(context: Context, kind: String) {
        val v = vibrator(context)?.takeIf { it.hasVibrator() } ?: return
        val soft = v.hasAmplitudeControl()
        val effect = when (kind) {
            "HUG" -> if (soft) VibrationEffect.createOneShot(600, 70) else VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
            "HEART" -> VibrationEffect.createWaveform(longArrayOf(0, 60, 120, 90), -1)
            "WAVE" -> VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40, 80, 40), -1)
            "TIMER" -> VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 300), -1)
            else -> VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { v.vibrate(effect) }
    }

    /** A short chime, on the notification volume so the phone's own settings decide. */
    fun chime() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 900)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 1_200)
        }
    }
}

val TOUCHES = listOf("HEART" to "♡", "HUG" to "🤗", "WAVE" to "👋", "TAP" to "👆")
private fun glyph(kind: String) = TOUCHES.firstOrNull { it.first == kind }?.second ?: "♡"
private fun words(kind: String) = when (kind) {
    "HUG" -> "a hug"; "WAVE" -> "a wave"; "TAP" -> "a tap"; else -> "a heart"
}
private fun firstName(p: MomentParticipantDto?) = p?.displayName?.trim()?.substringBefore(' ')?.ifBlank { null } ?: "Someone"

/**
 * A touch arriving: felt in the hand, seen for a moment, gone. Nothing is
 * kept, and nothing asks for an answer.
 */
@Composable
fun TouchArrivals(room: MomentRoomState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<MomentTouch?>(null) }
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(room) {
        room.touches.collect { t ->
            Haptics.feel(context, t.kind)
            shown = t
            count++
        }
    }
    LaunchedEffect(count) { if (count > 0) { delay(2_600); shown = null } }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = shown != null,
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            val t = shown
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(glyph(t?.kind ?: "HEART"), fontSize = 88.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.45f)) {
                    Text(
                        "${t?.fromName?.substringBefore(' ') ?: "Someone"} sent ${if (t?.to != null) "you " else ""}${words(t?.kind ?: "HEART")}",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Send a touch: to everyone here, or to one person. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TouchSheet(participants: List<MomentParticipantDto>, me: String?, onSend: (kind: String, to: String?) -> Unit) {
    var to by remember { mutableStateOf<String?>(null) }
    val others = participants.filter { it.userId != me }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("Send a touch", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
        Text("Felt on their phone, gone a moment later.", color = ViroColors.textMuted, style = MaterialTheme.typography.bodyMedium)
        if (others.size > 1) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = to == null, onClick = { to = null }, label = { Text("Everyone") })
                others.forEach { p -> FilterChip(selected = to == p.userId, onClick = { to = p.userId }, label = { Text(firstName(p)) }) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TOUCHES.forEach { (kind, g) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onSend(kind, to) }.padding(8.dp)) {
                    Text(g, fontSize = 40.sp)
                    Text(words(kind).removePrefix("a "), color = ViroColors.textMuted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** The touch button: tap for a heart to everyone, hold to choose. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TouchButton(onHeart: () -> Unit, onChoose: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.12f),
        modifier = Modifier.size(56.dp).combinedClickable(
            onClickLabel = "Send a heart to everyone",
            onLongClickLabel = "Choose a touch",
            onClick = onHeart,
            onLongClick = onChoose,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) { Text("♡", color = Color.White, fontSize = 24.sp) }
    }
}

// ------------------------------------------------------------------ timer

private fun mmss(ms: Long): String {
    val s = ((ms + 999) / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** How long is left, on the server's clock. */
fun timerLeftMs(t: MomentTimerDto, serverNow: Long): Long? = when (t.status) {
    "RUNNING" -> t.endsAt?.let { it - serverNow }
    "PAUSED" -> t.remainingMs
    else -> null
}

/**
 * One kitchen timer the whole room sees. Anyone can pause it or add a minute;
 * when it runs out, every phone chimes and says what it was for.
 */
object TimerModule : MomentModule {
    override val key = "TIMER"

    @Composable
    override fun Primary(ctx: MomentRoomContext, modifier: Modifier) = Secondary(ctx, modifier)

    @Composable
    override fun Secondary(ctx: MomentRoomContext, modifier: Modifier) {
        val timer by ctx.room.timer.collectAsState()
        val scope = rememberCoroutineScope()
        val t = timer
        val left = t?.let { timerLeftMs(it, ctx.room.serverNow()) }
        val done = t?.status == "RUNNING" && left != null && left <= 0
        // Rings once per timer, on every phone, when it reaches zero here.
        var rungFor by remember { mutableStateOf<Int?>(null) }
        val context = LocalContext.current
        LaunchedEffect(done, t?.revision) {
            if (done && t != null && rungFor != t.revision) {
                rungFor = t.revision
                Haptics.feel(context, "TIMER")
                Haptics.chime()
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = if (done) 0.7f else 0.45f), modifier = modifier) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⏱", fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    if (t == null || t.status == "NONE") {
                        Text("No timer running", color = Color.White.copy(alpha = 0.7f))
                    } else if (done) {
                        Text("${t.label ?: "Time"} is ready", color = Color.White, fontWeight = FontWeight.Medium)
                    } else {
                        Text(mmss(left ?: 0), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        Text(
                            (t.label ?: "Timer") + if (t.status == "PAUSED") " · paused" else "",
                            color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (t != null && t.status != "NONE") {
                    if (!done) {
                        TextButton(onClick = { scope.launch { ctx.room.timer(MomentTimerBody(if (t.status == "PAUSED") "RESUME" else "PAUSE")) } }) {
                            Text(if (t.status == "PAUSED") "Resume" else "Pause", color = Color.White)
                        }
                    }
                    TextButton(onClick = { scope.launch { ctx.room.timer(MomentTimerBody("ADD", addMs = 60_000)) } }) {
                        Text("+1 min", color = Color.White)
                    }
                }
                TextButton(onClick = {
                    scope.launch {
                        if (t != null && t.status != "NONE") ctx.room.timer(MomentTimerBody("CANCEL"))
                        ctx.room.change(MomentRoomChangeBody(op = "REMOVE", module = "TIMER"))
                    }
                }) { Text(if (done) "Done" else "✕", color = Color.White.copy(alpha = 0.8f)) }
            }
        }
    }
}

/** Setting the room's timer: a few everyday lengths and what it is for. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerSheet(onStart: (minutes: Int, label: String?) -> Unit) {
    var minutes by rememberSaveable { mutableIntStateOf(10) }
    var label by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("Set a timer for everyone", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 3, 5, 10, 15, 20, 30, 45, 60).forEach { m ->
                FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") })
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = label, onValueChange = { label = it.take(40) }, label = { Text("What's it for? (rice, tea…)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onStart(minutes, label.trim().ifBlank { null }) }, modifier = Modifier.fillMaxWidth()) {
            Text("Start the timer")
        }
    }
}

// ----------------------------------------------------------------- choice

/**
 * A question for the room: everyone answers, everyone sees the answers, and
 * whoever asked chooses.
 */
object ChoiceModule : MomentModule {
    override val key = "CHOICE"

    @Composable
    override fun Primary(ctx: MomentRoomContext, modifier: Modifier) = Secondary(ctx, modifier)

    @Composable
    override fun Secondary(ctx: MomentRoomContext, modifier: Modifier) {
        val choice by ctx.room.choice.collectAsState()
        val scope = rememberCoroutineScope()
        var asking by remember { mutableStateOf(false) }
        val c = choice
        Surface(shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = 0.45f), modifier = modifier) {
            Column(Modifier.padding(14.dp)) {
                if (c == null || c.status == "NONE") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Help me choose", color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = { asking = true }) { Text("Ask everyone", color = Color.White) }
                        TextButton(onClick = { scope.launch { ctx.room.change(MomentRoomChangeBody(op = "REMOVE", module = "CHOICE")) } }) {
                            Text("✕", color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                } else {
                    val asker = ctx.participants.firstOrNull { it.userId == c.askedBy }
                    Text(c.question ?: "", color = Color.White, fontWeight = FontWeight.Medium)
                    Text(if (c.askedBy == ctx.me) "You asked" else "${firstName(asker)} asked",
                        color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    val picks = c.picks ?: emptyMap()
                    val mine = picks[ctx.me]
                    (c.options ?: emptyList()).forEach { o ->
                        val who = picks.filterValues { it == o.id }.keys.map { id ->
                            if (id == ctx.me) "you" else firstName(ctx.participants.firstOrNull { it.userId == id })
                        }
                        val chosen = c.status == "DECIDED" && c.decided == o.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = if (chosen) 0.35f else if (mine == o.id) 0.22f else 0.08f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(enabled = c.status == "OPEN") {
                                scope.launch { ctx.room.choice(MomentChoiceBody("PICK", optionId = if (mine == o.id) null else o.id)) }
                            },
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text((if (chosen) "✓ " else "") + o.text, color = Color.White)
                                    if (who.isNotEmpty()) {
                                        Text(who.joinToString(", "), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (c.status == "OPEN" && c.askedBy == ctx.me) {
                                    TextButton(onClick = { scope.launch { ctx.room.choice(MomentChoiceBody("DECIDE", optionId = o.id)) } }) {
                                        Text("Choose this", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                    if (c.status == "DECIDED" || c.askedBy == ctx.me) {
                        Row {
                            TextButton(onClick = { asking = true }) { Text("Ask something else", color = Color.White) }
                            TextButton(onClick = {
                                scope.launch {
                                    ctx.room.choice(MomentChoiceBody("CLEAR"))
                                    ctx.room.change(MomentRoomChangeBody(op = "REMOVE", module = "CHOICE"))
                                }
                            }) { Text("Done", color = Color.White.copy(alpha = 0.8f)) }
                        }
                    }
                }
            }
        }
        if (asking) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { asking = false }) {
                Surface(shape = RoundedCornerShape(24.dp), color = ViroColors.surface) {
                    ChoiceSheet(onAsk = { q, options ->
                        asking = false
                        scope.launch { ctx.room.choice(MomentChoiceBody("ASK", question = q, options = options)) }
                    })
                }
            }
        }
    }
}

/** Writing the question: two to six options, nothing fancier. */
@Composable
fun ChoiceSheet(onAsk: (question: String, options: List<String>) -> Unit) {
    var question by rememberSaveable { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    val ready = question.isNotBlank() && options.count { it.isNotBlank() } >= 2
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("Ask everyone", color = ViroColors.textPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = question, onValueChange = { question = it.take(120) }, label = { Text("Your question") },
            modifier = Modifier.fillMaxWidth())
        options.forEachIndexed { i, text ->
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = text, onValueChange = { options[i] = it.take(60) }, label = { Text("Option ${i + 1}") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (options.size < 6) {
            TextButton(onClick = { options.add("") }) { Text("Add an option") }
        }
        Spacer(Modifier.height(8.dp))
        Button(enabled = ready, onClick = { onAsk(question.trim(), options.map { it.trim() }.filter { it.isNotEmpty() }) },
            modifier = Modifier.fillMaxWidth()) { Text("Ask") }
    }
}
