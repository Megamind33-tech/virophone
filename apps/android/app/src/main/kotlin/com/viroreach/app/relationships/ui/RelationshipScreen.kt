package com.viroreach.app.relationships.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.app.messaging.ui.Vibe
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.network.DateBody
import com.viroreach.core.network.RelationshipBody
import com.viroreach.core.network.TimelineItemDto
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val PERSONAL = listOf(
    "PARTNER" to "Partner", "FAMILY" to "Family", "PARENT" to "Parent", "CHILD" to "Child",
    "FRIEND" to "Friend", "BEST_FRIEND" to "Best friend", "RELATIVE" to "Relative",
)
private val PROFESSIONAL = listOf(
    "CLIENT" to "Client", "CUSTOMER" to "Customer", "EMPLOYEE" to "Employee", "MANAGER" to "Manager",
    "BUSINESS_PARTNER" to "Business partner", "SUPPLIER" to "Supplier", "PROSPECT" to "Prospect", "COLLEAGUE" to "Colleague",
)
private val DATE_KINDS = listOf(
    "BIRTHDAY" to "Birthday", "ANNIVERSARY" to "Anniversary", "FIRST_MEETING" to "First meeting",
    "WEDDING_ANNIVERSARY" to "Wedding anniversary", "GRADUATION" to "Graduation", "CHILD_BIRTHDAY" to "Child's birthday",
    "CONTRACT_RENEWAL" to "Contract renewal", "PAYMENT" to "Payment date", "FOLLOW_UP" to "Follow-up date",
    "BUSINESS_REVIEW" to "Business review", "CUSTOM" to "Other",
)
private val WEEKDAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

/**
 * One person, privately: what they are to you, how often you want to be in
 * touch, the dates that matter, the promises you made, the Loops you share,
 * and the Moments you have had. Nothing here is shown to them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelationshipScreen(
    session: SessionManager,
    peerUserId: String?,
    phone: String?,
    name: String,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val overview by session.relationships.overview.collectAsState()
    val rel = remember(overview, peerUserId, phone) { session.relationships.relationshipFor(peerUserId, phone) }

    var category by remember(rel?.id) { mutableStateOf(rel?.category ?: "PERSONAL") }
    var type by remember(rel?.id) { mutableStateOf(rel?.relationshipType ?: "FRIEND") }
    var cadence by remember(rel?.id) { mutableStateOf(rel?.targetCadence) }
    var count by remember(rel?.id) { mutableIntStateOf(rel?.targetCount ?: 1) }
    var everyDays by remember(rel?.id) { mutableIntStateOf(rel?.targetEveryDays ?: 7) }
    var weekday by remember(rel?.id) { mutableIntStateOf(rel?.targetWeekday ?: 1) }
    var targetLabel by remember(rel?.id) { mutableStateOf(rel?.targetLabel.orEmpty()) }
    var vibe by remember(rel?.id) { mutableStateOf(rel?.vibeExplicit) }
    var reminders by remember(rel?.id) { mutableStateOf(rel?.remindersEnabled != false) }
    var notes by remember(rel?.id) { mutableStateOf(rel?.notes.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var addingDate by remember { mutableStateOf(false) }
    var timeline by remember(rel?.id) { mutableStateOf<List<TimelineItemDto>>(emptyList()) }

    LaunchedEffect(Unit) { session.relationships.refresh() }
    LaunchedEffect(rel?.id) {
        rel?.id?.let { id -> session.relationships.timeline(id).onSuccess { timeline = it.items.orEmpty() } }
    }

    suspend fun save(): String? {
        saving = true
        val r = session.relationships.save(
            RelationshipBody(
                subjectUserId = peerUserId,
                subjectPhone = phone,
                displayName = name,
                category = category,
                relationshipType = type,
                // "" rather than null: Gson drops nulls, and "Automatic" /
                // "None" must actually clear what was set before.
                vibe = vibe ?: "",
                targetCadence = cadence ?: "",
                targetCount = count,
                targetEveryDays = everyDays,
                targetWeekday = weekday,
                targetLabel = targetLabel.ifBlank { null },
                remindersEnabled = reminders,
                notes = notes,
            ),
        )
        saving = false
        return r.getOrElse {
            Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
            null
        }
    }

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(ViroColors.NavyBackground).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Only you can see this page.", color = ViroColors.textSecondary, fontSize = 12.sp)
            }
            TextButton(onClick = { scope.launch { if (save() != null) Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() } }, enabled = !saving) {
                Text(if (saving) "Saving…" else "Save", color = ViroColors.accent)
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rel?.health?.let { h ->
                Card(title = "Communication health") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HealthPill(h.code)
                        Spacer(Modifier.width(8.dp))
                        Text(h.text, color = Color.White, modifier = Modifier.weight(1f))
                    }
                    rel.lastInteractionAt?.let {
                        Text("Last in touch: ${it.take(10)}", color = ViroColors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    rel.id.let { id ->
                        TextButton(onClick = {
                            scope.launch {
                                session.relationships.checkIn(id, "Outside Viro").onSuccess {
                                    Toast.makeText(context, "Logged. Viro counts it towards your target.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) { Text("I checked in outside Viro") }
                    }
                }
            }

            Card(title = "Relationship") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = category == "PERSONAL", onClick = { category = "PERSONAL"; if (type in PROFESSIONAL.map { it.first }) type = "FRIEND" }, label = { Text("Personal") })
                    FilterChip(selected = category == "PROFESSIONAL", onClick = { category = "PROFESSIONAL"; if (type in PERSONAL.map { it.first }) type = "CLIENT" }, label = { Text("Professional") })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (if (category == "PERSONAL") PERSONAL else PROFESSIONAL).forEach { (k, l) ->
                        FilterChip(selected = type == k, onClick = { type = k }, label = { Text(l) })
                    }
                }
            }

            Card(title = "Communication target") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "None", "DAILY" to "Daily", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly", "EVERY_N_DAYS" to "Every N days", "WEEKDAY" to "A set day").forEach { (k, l) ->
                        FilterChip(selected = cadence == k, onClick = { cadence = k }, label = { Text(l) })
                    }
                }
                when (cadence) {
                    "WEEKLY", "MONTHLY" -> Stepper("At least", count, 1, 14, if (cadence == "WEEKLY") "times a week" else "times a month") { count = it }
                    "EVERY_N_DAYS" -> Stepper("Every", everyDays, 1, 90, "days") { everyDays = it }
                    "WEEKDAY" -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            WEEKDAYS.forEachIndexed { i, d -> FilterChip(selected = weekday == i, onClick = { weekday = i }, label = { Text(d.take(3)) }) }
                        }
                        OutlinedTextField(
                            value = targetLabel, onValueChange = { targetLabel = it.take(80) },
                            label = { Text("What happens that day (e.g. Weekly review)") }, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Text(
                    "${name.split(" ").first()} is never told about targets. They only shape your reminders.",
                    color = ViroColors.textSecondary, fontSize = 12.sp,
                )
            }

            Card(title = "Important dates") {
                rel?.dates.orEmpty().forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${d.label ?: d.kind} — ${d.day} ${java.time.Month.of(d.month).name.lowercase().replaceFirstChar { it.uppercase() }}${d.year?.let { " $it" } ?: ""}",
                            color = Color.White, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { scope.launch { session.relationships.removeDate(d.id) } }) {
                            Icon(Icons.Default.Close, "Remove", tint = ViroColors.textSecondary)
                        }
                    }
                }
                TextButton(onClick = { addingDate = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add a date") }
            }

            val commitments = rel?.openCommitments.orEmpty()
            if (commitments.isNotEmpty()) {
                Card(title = "Commitments") {
                    commitments.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.text.replaceFirstChar { it.uppercase() }, color = Color.White)
                                Text(c.dueAt.take(16).replace('T', ' '), color = ViroColors.textSecondary, fontSize = 12.sp)
                            }
                            TextButton(onClick = { scope.launch { session.relationships.setCommitmentStatus(c.id, "DONE") } }) { Text("Done") }
                            TextButton(onClick = { scope.launch { session.relationships.setCommitmentStatus(c.id, "DISMISSED") } }) { Text("Drop") }
                        }
                    }
                }
            }

            Card(title = "Loops") {
                val loops = rel?.loops.orEmpty()
                if (loops.isEmpty()) Text("No Loops yet. Start one from the chat menu.", color = ViroColors.textSecondary)
                loops.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("🔁 ${l.title}", color = Color.White, modifier = Modifier.weight(1f))
                        Text("${l.completedTotal ?: 0} shared", color = ViroColors.textSecondary)
                    }
                }
                TextButton(onClick = onOpenChat) { Text("Open chat") }
            }

            Card(title = "Vibe") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = vibe == null, onClick = { vibe = null }, label = { Text("Automatic") })
                    Vibe.choosable.forEach { v -> FilterChip(selected = vibe == v.key, onClick = { vibe = v.key }, label = { Text(v.label) }) }
                }
            }

            Card(title = "Reminders & notes") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reminders about ${name.split(" ").first()}", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = reminders, onCheckedChange = { reminders = it })
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it.take(4000) }, label = { Text("Private notes") }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp))
            }

            if (timeline.isNotEmpty()) {
                Card(title = "Moments") {
                    timeline.take(40).forEach { t -> MomentRow(t) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (addingDate) {
        AddDateDialog(onDismiss = { addingDate = false }) { body ->
            addingDate = false
            scope.launch {
                val id = rel?.id ?: save() ?: return@launch
                session.relationships.addDate(id, body).onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ViroColors.NavySurface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = ViroColors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Stepper(prefix: String, value: Int, min: Int, max: Int, suffix: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(prefix, color = Color.White)
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }) { Icon(Icons.Default.Remove, "Less", tint = Color.White) }
        Text("$value", color = Color.White, fontWeight = FontWeight.Bold)
        IconButton(onClick = { onChange((value + 1).coerceAtMost(max)) }) { Icon(Icons.Default.Add, "More", tint = Color.White) }
        Text(suffix, color = Color.White)
    }
}

@Composable
private fun MomentRow(t: TimelineItemDto) {
    val date = runCatching { LocalDate.parse(t.day).format(DateTimeFormatter.ofPattern("d MMM")) }.getOrDefault(t.day)
    val icon = when (t.kind) {
        "CALL" -> "📞"
        "LOOP" -> "🔁"
        "PHOTO" -> "📷"
        "DATE" -> "🎉"
        "COMMITMENT" -> "📌"
        "COMMITMENT_DONE" -> "✅"
        "CHECKIN" -> "🤝"
        "FIRST" -> "✨"
        else -> "•"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(date, color = ViroColors.textSecondary, fontSize = 13.sp, modifier = Modifier.width(56.dp))
        Text("$icon ${t.title}${t.detail?.let { " — $it" } ?: ""}", color = Color.White, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddDateDialog(onDismiss: () -> Unit, onAdd: (DateBody) -> Unit) {
    var kind by remember { mutableStateOf("BIRTHDAY") }
    var label by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    var month by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var before by remember { mutableIntStateOf(1) }
    val d = day.toIntOrNull()
    val m = month.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an important date") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DATE_KINDS.forEach { (k, l) -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(l) }) }
                }
                if (kind == "CUSTOM") OutlinedTextField(value = label, onValueChange = { label = it.take(80) }, label = { Text("Name") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = day, onValueChange = { day = it.filter(Char::isDigit).take(2) }, label = { Text("Day") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = month, onValueChange = { month = it.filter(Char::isDigit).take(2) }, label = { Text("Month") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = year, onValueChange = { year = it.filter(Char::isDigit).take(4) }, label = { Text("Year*") }, modifier = Modifier.weight(1.2f), singleLine = true)
                }
                Text("*Only for one-off dates. Leave empty for dates that repeat every year.", color = ViroColors.textSecondary, fontSize = 12.sp)
                Stepper("Remind me", before, 0, 14, "days before") { before = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = d != null && m != null && d in 1..31 && m in 1..12,
                onClick = {
                    onAdd(
                        DateBody(
                            kind = kind,
                            label = label.ifBlank { null },
                            month = m!!,
                            day = d!!,
                            year = year.toIntOrNull(),
                            remindDaysBefore = before,
                        ),
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
