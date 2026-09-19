package com.viroreach.app.relationships.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.app.consumer.ChatRoute
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.network.AchievementDto
import com.viroreach.core.network.AttentionDto
import kotlinx.coroutines.launch

/**
 * Connections — not another contact list. It answers one question:
 * who needs my attention? Built only from targets, dates, Loops and promises
 * the user set, and worded to remind, never to judge.
 */
@Composable
fun ConnectionsDashboard(
    session: SessionManager,
    onOpenChat: (ChatRoute) -> Unit,
    onCall: (peerUserId: String?, phone: String?, name: String) -> Unit,
    onOpenRelationship: (peerUserId: String?, phone: String?, name: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val overview by session.relationships.overview.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        session.relationships.refresh(force = true)
        loading = false
    }

    val ov = overview
    if (ov == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(color = ViroColors.accent)
            else Text("Connections needs a connection to load.", color = ViroColors.textSecondary)
        }
        return
    }

    fun chatWith(a: AttentionDto) {
        onOpenChat(ChatRoute(conversationId = null, peerName = a.name, peerUserId = a.subjectUserId, peerPhoneE164 = a.subjectPhone))
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ov.brief?.let { b ->
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(ViroColors.accent.copy(alpha = 0.16f)).padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Tune, "Reminder settings", tint = ViroColors.textSecondary) }
                    }
                    Text(b.summary, color = ViroColors.textSecondary)
                }
            }
        }

        if (ov.relationships.isNullOrEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🤝", fontSize = 40.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Who matters to you?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Open a chat and tap the person's name to add a relationship: a target, important dates and Loops. Viro remembers, and reminds you at the right moment. Only you see it.",
                        color = ViroColors.textSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        val attention = ov.attention.orEmpty()
        if (attention.isNotEmpty()) {
            item { SectionTitle("Today") }
            items(attention, key = { "${it.relationshipId}-${it.code}-${it.loopId}" }) { a ->
                AttentionCard(
                    a = a,
                    onMessage = { chatWith(a) },
                    onCall = if (a.subjectUserId != null) ({ onCall(a.subjectUserId, a.subjectPhone, a.name) }) else null,
                    onOpen = { onOpenRelationship(a.subjectUserId, a.subjectPhone, a.name) },
                    onDone = a.commitmentId?.let { id ->
                        {
                            scope.launch {
                                session.relationships.setCommitmentStatus(id, "DONE")
                                    .onSuccess { Toast.makeText(context, "Done. Nicely kept.", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    },
                    onCheckedIn = a.relationshipId?.takeIf { it.isNotBlank() && a.code in setOf("DUE_TODAY", "NEEDS_ATTENTION", "FOLLOW_UP_DUE") }?.let { rid ->
                        { scope.launch { session.relationships.checkIn(rid, "Outside Viro") } }
                    },
                )
            }
        } else if (!ov.relationships.isNullOrEmpty()) {
            item {
                Text("Nobody needs your attention right now. 🌿", color = ViroColors.textSecondary, modifier = Modifier.padding(vertical = 4.dp))
            }
        }

        val coming = ov.comingUp.orEmpty()
        if (coming.isNotEmpty()) {
            item { SectionTitle("Coming up") }
            coming.groupBy { it.label }.forEach { (label, list) ->
                item(key = "cu-$label") {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ViroColors.NavySurface).padding(14.dp)) {
                        Text(label, color = ViroColors.textSecondary, fontSize = 13.sp)
                        list.forEach { c ->
                            Text("${c.icon ?: "•"} ${c.text}", color = Color.White, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }

        val targets = ov.targets.orEmpty()
        if (targets.isNotEmpty()) {
            item { SectionTitle("Your targets") }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ViroColors.NavySurface).padding(14.dp)) {
                    targets.forEach { t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.label, color = Color.White, modifier = Modifier.weight(1f))
                            Text("${t.met} / ${t.total} on track", color = ViroColors.textSecondary)
                        }
                        LinearProgressIndicator(
                            progress = { if (t.total == 0) 0f else t.met.toFloat() / t.total },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = ViroColors.accent,
                            trackColor = ViroColors.NavySurfaceElevated,
                        )
                    }
                }
            }
        }

        val moments = ov.moments.orEmpty()
        if (moments.isNotEmpty()) {
            item { SectionTitle("This month") }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ViroColors.NavySurface).padding(14.dp)) {
                    moments.forEach { Text("• $it", color = Color.White, modifier = Modifier.padding(vertical = 3.dp)) }
                }
            }
        }

        val achievements = ov.achievements.orEmpty()
        if (achievements.isNotEmpty()) {
            item { SectionTitle("Achievements") }
            items(achievements.take(6), key = { it.key }) { a -> AchievementRow(a) { shareAchievement(context, a) } }
        }

        if (!ov.relationships.isNullOrEmpty()) {
            item { SectionTitle("People who matter") }
            items(ov.relationships.orEmpty(), key = { "rel-${it.id}" }) { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                        onOpenRelationship(r.subjectUserId, r.subjectPhone, r.displayName ?: "Contact")
                    }.background(ViroColors.NavySurface).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.icon ?: "🤝", fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.displayName ?: "Contact", color = Color.White, fontWeight = FontWeight.Medium)
                        Text(r.targetText ?: "No target", color = ViroColors.textSecondary, fontSize = 12.sp)
                    }
                    HealthPill(r.health?.code)
                }
            }
        }
    }

    if (showSettings) ReminderSettingsDialog(session) { showSettings = false }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = ViroColors.textSecondary, fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun HealthPill(code: String?) {
    val (label, color) = when (code) {
        "ON_TRACK" -> "On track" to Color(0xFF4CD964)
        "COMMITMENT_DUE" -> "Promise due" to Color(0xFFFF6F91)
        "FOLLOW_UP_DUE" -> "Follow-up due" to Color(0xFFFFB35C)
        "DUE_TODAY" -> "Due today" to Color(0xFFFFB35C)
        "NEEDS_ATTENTION" -> "Needs attention" to Color(0xFFFFB35C)
        "LOOP_WAITING" -> "Loop waiting" to Color(0xFFA28BFF)
        "DATE_SOON" -> "Date soon" to Color(0xFF6EE7FF)
        else -> return
    }
    Text(
        label,
        color = color,
        fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun AttentionCard(
    a: AttentionDto,
    onMessage: () -> Unit,
    onCall: (() -> Unit)?,
    onOpen: () -> Unit,
    onDone: (() -> Unit)?,
    onCheckedIn: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ViroColors.NavySurface).clickable(onClick = onOpen).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.icon ?: "❤️", fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text(a.name, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            HealthPill(a.code)
        }
        Text(a.text, color = ViroColors.textSecondary, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (a.code == "LOOP_WAITING") {
                TextButton(onClick = onMessage) { Text("Answer Loop") }
            } else {
                TextButton(onClick = onMessage) { Text("Message") }
                onCall?.let { TextButton(onClick = it) { Text("Call") } }
            }
            onDone?.let { TextButton(onClick = it) { Text("Done") } }
            onCheckedIn?.let { TextButton(onClick = it) { Text("I checked in") } }
        }
    }
}

@Composable
private fun AchievementRow(a: AchievementDto, onShare: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ViroColors.NavySurface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✨", fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(a.detail, color = ViroColors.textSecondary, fontSize = 13.sp)
        }
        IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share", tint = ViroColors.textSecondary) }
    }
}

private fun shareAchievement(context: android.content.Context, a: AchievementDto) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "${a.title} — ${a.detail} (Viro)")
    }
    context.startActivity(Intent.createChooser(intent, "Share achievement"))
}

@Composable
fun ReminderSettingsDialog(session: SessionManager, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf<com.viroreach.core.network.ReminderSettingsDto?>(null) }
    LaunchedEffect(Unit) { s = session.relationships.reminderSettings() ?: com.viroreach.core.network.ReminderSettingsDto() }
    val cur = s
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminders") },
        text = {
            if (cur == null) CircularProgressIndicator() else Column {
                Text("Viro stays quiet unless it has something worth saying.", color = ViroColors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                @Composable
                fun toggle(label: String, value: Boolean?, set: (Boolean) -> com.viroreach.core.network.ReminderSettingsDto) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f))
                        Switch(checked = value != false, onCheckedChange = { s = set(it) })
                    }
                }
                toggle("Morning brief", cur.briefEnabled) { cur.copy(briefEnabled = it) }
                toggle("Personal reminders", cur.personalReminders) { cur.copy(personalReminders = it) }
                toggle("Professional reminders", cur.professionalReminders) { cur.copy(professionalReminders = it) }
                toggle("Important dates", cur.dateReminders) { cur.copy(dateReminders = it) }
                toggle("Loops", cur.loopNotifications) { cur.copy(loopNotifications = it) }
                toggle("Achievements", cur.achievementNotifications) { cur.copy(achievementNotifications = it) }
                Spacer(Modifier.height(8.dp))
                Text("How often", color = ViroColors.textSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("LOW" to "Rarely", "NORMAL" to "Normal", "HIGH" to "More").forEach { (k, l) ->
                        FilterChip(selected = (cur.frequency ?: "NORMAL") == k, onClick = { s = cur.copy(frequency = k) }, label = { Text(l) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = cur.briefTime ?: "08:00", onValueChange = { v -> if (v.length <= 5) s = cur.copy(briefTime = v) }, label = { Text("Brief at") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = cur.quietStart ?: "21:30", onValueChange = { v -> if (v.length <= 5) s = cur.copy(quietStart = v) }, label = { Text("Quiet from") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = cur.quietEnd ?: "07:00", onValueChange = { v -> if (v.length <= 5) s = cur.copy(quietEnd = v) }, label = { Text("Until") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cur2 = s ?: return@TextButton onDismiss()
                scope.launch {
                    session.relationships.saveReminderSettings(cur2)
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
