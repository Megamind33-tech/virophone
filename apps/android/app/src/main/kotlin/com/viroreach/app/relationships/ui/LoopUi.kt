package com.viroreach.app.relationships.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.viroreach.app.messaging.ui.Vibe
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.network.LoopAnswerDto
import com.viroreach.core.network.LoopBody
import com.viroreach.core.network.LoopDto

/** Ready-made Loops, offered by relationship; the user can write their own. */
data class LoopTemplate(val title: String, val prompt: String, val frequency: String, val time: String, val response: String = "ANY", val choices: List<String>? = null)

val LOOP_TEMPLATES: Map<String, List<LoopTemplate>> = mapOf(
    "Couple" to listOf(
        LoopTemplate("Evening Check-In", "What was the best part of your day?", "DAILY", "20:00"),
        LoopTemplate("Grateful For", "One thing you're grateful for about us this week?", "WEEKLY", "19:00"),
        LoopTemplate("Mood", "How are you feeling right now?", "DAILY", "13:00", "EMOJI"),
    ),
    "Family" to listOf(
        LoopTemplate("How Are You", "How are you doing today?", "DAILY", "18:00"),
        LoopTemplate("Sunday Catch-Up", "What happened this week?", "CUSTOM", "16:00"),
    ),
    "Friends" to listOf(
        LoopTemplate("Made Me Laugh", "One thing that made you laugh today.", "DAILY", "21:00"),
        LoopTemplate("Weekend Plans", "What are you up to this weekend?", "CUSTOM", "17:00"),
    ),
    "Work" to listOf(
        LoopTemplate("Tomorrow's Priority", "What is the most important thing we need to complete tomorrow?", "WEEKDAYS", "17:00", "TEXT"),
        LoopTemplate("Blockers", "What is blocking you today?", "WEEKDAYS", "09:30", "TEXT"),
        LoopTemplate("Weekly Wins", "What did we accomplish this week?", "CUSTOM", "16:00", "TEXT"),
        LoopTemplate("On Track?", "Are we on track for this week's goal?", "WEEKLY", "10:00", "CHOICE", listOf("On track", "At risk", "Blocked")),
    ),
)

private val FREQUENCIES = listOf(
    "DAILY" to "Daily", "WEEKDAYS" to "Weekdays", "WEEKLY" to "Weekly",
    "MONTHLY" to "Monthly", "CUSTOM" to "Custom days", "ONCE" to "One time",
)
private val RESPONSES = listOf(
    "ANY" to "Any", "TEXT" to "Text", "VOICE" to "Voice", "PHOTO" to "Photo", "EMOJI" to "Emoji", "CHOICE" to "Choice",
)
private val DAY_LABELS = listOf("S", "M", "T", "W", "T", "F", "S")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoopCreateDialog(
    vibe: Vibe,
    suggestedGroup: String,
    onDismiss: () -> Unit,
    onCreate: (LoopBody) -> Unit,
) {
    var group by remember { mutableStateOf(suggestedGroup) }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf("DAILY") }
    var time by remember { mutableStateOf("19:00") }
    var response by remember { mutableStateOf("ANY") }
    var choices by remember { mutableStateOf("") }
    var reciprocal by remember { mutableStateOf(group != "Work") }
    var days by remember { mutableIntStateOf(1 shl 0) }

    fun apply(t: LoopTemplate) {
        title = t.title
        prompt = t.prompt
        frequency = t.frequency
        time = t.time
        response = t.response
        choices = t.choices?.joinToString(", ").orEmpty()
        if (t.frequency == "CUSTOM") days = if (t.title.contains("Weekend") || t.title.contains("Weekly Wins")) 1 shl 5 else 1 shl 0
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f), shape = RoundedCornerShape(24.dp), color = ViroColors.NavySurface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("Start a Loop", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "A question you both answer on a rhythm. Nobody is punished for missing a day.",
                    color = ViroColors.textSecondary, fontSize = 14.sp,
                )
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LOOP_TEMPLATES.keys.forEach { g ->
                        FilterChip(selected = group == g, onClick = { group = g; reciprocal = g != "Work" }, label = { Text(g) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                LOOP_TEMPLATES[group].orEmpty().forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { apply(t) }
                            .background(if (title == t.title) vibe.accent.copy(alpha = 0.18f) else Color.Transparent)
                            .padding(10.dp),
                    ) {
                        Column {
                            Text(t.title, color = Color.White, fontWeight = FontWeight.Medium)
                            Text(t.prompt, color = ViroColors.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = prompt, onValueChange = { prompt = it.take(280) }, label = { Text("Question") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("How often", color = ViroColors.textSecondary, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FREQUENCIES.forEach { (k, l) -> FilterChip(selected = frequency == k, onClick = { frequency = k }, label = { Text(l) }) }
                }
                if (frequency == "CUSTOM") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAY_LABELS.forEachIndexed { i, l ->
                            val on = days and (1 shl i) != 0
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(if (on) vibe.accent else ViroColors.NavySurfaceElevated)
                                    .clickable { days = days xor (1 shl i) },
                                contentAlignment = Alignment.Center,
                            ) { Text(l, color = Color.White) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = time,
                    onValueChange = { v -> if (v.length <= 5) time = v },
                    label = { Text("Time (HH:MM)") },
                    singleLine = true,
                    modifier = Modifier.width(160.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("Answer with", color = ViroColors.textSecondary, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RESPONSES.forEach { (k, l) -> FilterChip(selected = response == k, onClick = { response = k }, label = { Text(l) }) }
                }
                if (response == "CHOICE") {
                    OutlinedTextField(value = choices, onValueChange = { choices = it }, label = { Text("Choices, separated by commas") }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reveal together", color = Color.White)
                        Text("Your answer stays hidden until they answer too.", color = ViroColors.textSecondary, fontSize = 12.sp)
                    }
                    Switch(checked = reciprocal, onCheckedChange = { reciprocal = it })
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = title.isNotBlank() && prompt.isNotBlank() && Regex("""^\d{2}:\d{2}$""").matches(time),
                        colors = ButtonDefaults.buttonColors(containerColor = vibe.accent),
                        onClick = {
                            onCreate(
                                LoopBody(
                                    title = title.trim(),
                                    prompt = prompt.trim(),
                                    frequency = frequency,
                                    daysMask = if (frequency == "CUSTOM") days else null,
                                    timeOfDay = time,
                                    timezone = java.util.TimeZone.getDefault().id,
                                    responseKind = response,
                                    choices = if (response == "CHOICE") choices.split(",").map { it.trim() }.filter { it.isNotEmpty() } else null,
                                    reciprocal = reciprocal,
                                ),
                            )
                        },
                    ) { Text("Start Loop") }
                }
            }
        }
    }
}

/**
 * One Loop's current occurrence inside the chat: the question, then either an
 * answer box, "Waiting for Sarah…", or both answers revealed.
 */
@Composable
fun LoopCard(
    loop: LoopDto,
    myUserId: String?,
    vibe: Vibe,
    nameOf: (String) -> String,
    onAnswerText: (LoopDto, String, String) -> Unit,
    onAnswerVoice: (LoopDto) -> Unit,
    onAnswerPhoto: (LoopDto) -> Unit,
    onPlayAnswer: (LoopAnswerDto) -> Unit,
    onMore: (LoopDto) -> Unit,
) {
    var text by remember(loop.id, loop.periodKey) { mutableStateOf("") }
    val others = loop.participants.orEmpty().filter { it != myUserId }
    val waiting = loop.waitingFor.orEmpty().filter { it != myUserId }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(vibe.accent.copy(alpha = 0.14f))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔁", fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(loop.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(momentsLine(loop), color = ViroColors.textSecondary, fontSize = 12.sp)
            }
            IconButton(onClick = { onMore(loop) }) { Icon(Icons.Default.MoreHoriz, "Loop options", tint = ViroColors.textSecondary) }
        }
        Spacer(Modifier.height(6.dp))
        Text(loop.prompt, color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        when {
            loop.active != true -> Text("Paused", color = ViroColors.textSecondary)
            loop.periodKey == null -> Text("Next one opens on its day.", color = ViroColors.textSecondary)
            loop.revealed == true -> loop.answers.orEmpty().forEach { a -> AnswerLine(a, if (a.userId == myUserId) "You" else nameOf(a.userId), vibe, onPlayAnswer) }
            loop.myAnswer != null -> {
                AnswerLine(loop.myAnswer!!, "You", vibe, onPlayAnswer)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (loop.reciprocal == true) "Waiting for ${waiting.joinToString { nameOf(it) }}… Both answers are revealed when they reply."
                    else "Sent.",
                    color = ViroColors.textSecondary, fontSize = 13.sp,
                )
                loop.answers.orEmpty().filter { it.userId != myUserId }.forEach { AnswerLine(it, nameOf(it.userId), vibe, onPlayAnswer) }
            }
            else -> {
                if (loop.answeredBy.orEmpty().any { it in others }) {
                    Text(
                        "${others.filter { it in loop.answeredBy.orEmpty() }.joinToString { nameOf(it) }} answered. Answer to see theirs ❤️",
                        color = vibe.accent, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                AnswerInput(loop, vibe, text, { text = it }, onAnswerText, onAnswerVoice, onAnswerPhoto)
            }
        }
    }
}

private fun momentsLine(loop: LoopDto): String {
    val freq = FREQUENCIES.firstOrNull { it.first == loop.frequency }?.second ?: loop.frequency
    val month = loop.completedThisMonth ?: 0
    return if (month > 0) "$freq · ${loop.timeOfDay} · $month shared this month" else "$freq · ${loop.timeOfDay}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerInput(
    loop: LoopDto,
    vibe: Vibe,
    text: String,
    onText: (String) -> Unit,
    onAnswerText: (LoopDto, String, String) -> Unit,
    onVoice: (LoopDto) -> Unit,
    onPhoto: (LoopDto) -> Unit,
) {
    val kind = loop.responseKind ?: "ANY"
    when (kind) {
        "CHOICE" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            loop.choices.orEmpty().forEach { c -> AssistChip(onClick = { onAnswerText(loop, "CHOICE", c) }, label = { Text(c) }) }
        }
        "EMOJI" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("😊", "😌", "🥰", "😴", "😤", "😢", "🤩", "😐").forEach { e ->
                Text(e, fontSize = 28.sp, modifier = Modifier.clickable { onAnswerText(loop, "EMOJI", e) })
            }
        }
        "VOICE" -> Button(onClick = { onVoice(loop) }, colors = ButtonDefaults.buttonColors(containerColor = vibe.accent)) {
            Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp)); Text("Answer with your voice")
        }
        "PHOTO" -> Button(onClick = { onPhoto(loop) }, colors = ButtonDefaults.buttonColors(containerColor = vibe.accent)) {
            Icon(Icons.Default.Photo, null); Spacer(Modifier.width(6.dp)); Text("Answer with a photo")
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                placeholder = { Text("Your answer") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            )
            if (text.isBlank() && kind == "ANY") {
                IconButton(onClick = { onVoice(loop) }) { Icon(Icons.Default.Mic, "Answer by voice", tint = vibe.accent) }
                IconButton(onClick = { onPhoto(loop) }) { Icon(Icons.Default.Photo, "Answer with a photo", tint = vibe.accent) }
            } else {
                IconButton(onClick = { onAnswerText(loop, "TEXT", text.trim()) }, enabled = text.isNotBlank()) {
                    Icon(Icons.Default.Send, "Send answer", tint = vibe.accent)
                }
            }
        }
    }
}

@Composable
private fun AnswerLine(a: LoopAnswerDto, who: String, vibe: Vibe, onPlay: (LoopAnswerDto) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(who, color = vibe.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
        when (a.kind) {
            "VOICE" -> TextButton(onClick = { onPlay(a) }) { Icon(Icons.Default.PlayArrow, null); Text("Voice answer") }
            "PHOTO" -> TextButton(onClick = { onPlay(a) }) { Icon(Icons.Default.Photo, null); Text("Photo answer") }
            "EMOJI" -> Text(a.text.orEmpty(), fontSize = 26.sp)
            else -> Text(a.text.orEmpty(), color = Color.White)
        }
    }
}

@Composable
fun LoopHistoryDialog(
    loop: LoopDto,
    periods: List<com.viroreach.core.network.LoopPeriodDto>,
    myUserId: String?,
    nameOf: (String) -> String,
    vibe: Vibe,
    onPlay: (LoopAnswerDto) -> Unit,
    onPauseToggle: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = ViroColors.NavySurface) {
            Column(Modifier.padding(18.dp).heightIn(max = 560.dp)) {
                Text(loop.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("${loop.completedTotal ?: 0} moments shared", color = ViroColors.textSecondary)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(periods, key = { it.periodKey }) { p ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(p.periodKey, color = ViroColors.textSecondary, fontSize = 12.sp)
                            if (p.answers.isNullOrEmpty()) Text("Hidden until you answer", color = ViroColors.textSecondary, fontSize = 13.sp)
                            p.answers.orEmpty().forEach { a -> AnswerLine(a, if (a.userId == myUserId) "You" else nameOf(a.userId), vibe, onPlay) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    onDelete?.let { TextButton(onClick = it) { Text("Delete", color = ViroColors.consumerError) } }
                    TextButton(onClick = onPauseToggle) { Text(if (loop.active == true) "Pause" else "Resume") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
