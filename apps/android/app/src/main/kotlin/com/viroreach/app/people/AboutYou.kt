package com.viroreach.app.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroContinueButton
import com.viroreach.core.designsystem.components.ViroErrorMessage
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import com.viroreach.core.network.AboutYouTopicDto
import com.viroreach.core.network.UpdateMeBody
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/**
 * The things somebody can say about themselves, and how each one is asked and
 * then shown.
 *
 * Each has two voices. [question] is asked of the person, in the second
 * person, the way a friend would ask it. [othersLabel] is how it reads to the
 * people allowed to see it, and is written to help them rather than to label
 * the person: somebody's fears appear as "Go gently with", because that is
 * the thing a friend can actually do with the knowledge.
 *
 * The order is deliberate. The light questions come first, so the setup
 * starts with what somebody loves rather than with what frightens them, and
 * the vulnerable ones come last, after trust has had a few screens to build.
 */
enum class AboutYouTopic(
    val key: String,
    val question: String,
    val hint: String,
    val placeholder: String,
    val othersLabel: String,
    /** Fears and weaknesses: private by default, never shareable with everyone. */
    val vulnerable: Boolean = false,
) {
    INTERESTS(
        "INTERESTS", "What do you love?",
        "The things you could talk about for hours.",
        "Cooking, football, old films…", "Loves",
    ),
    STRENGTHS(
        "STRENGTHS", "What are you good at?",
        "What people come to you for.",
        "Listening, fixing things…", "Good at",
    ),
    GOALS(
        "GOALS", "What are you working towards?",
        "This year, or just this week.",
        "Finishing my course…", "Working towards",
    ),
    DREAMS(
        "DREAMS", "What do you dream about?",
        "Big or small. Nobody is marking this.",
        "Seeing the sea, my own shop…", "Dreams of",
    ),
    WEAKNESSES(
        "WEAKNESSES", "What do you find hard?",
        "Only you can see this unless you choose otherwise.",
        "Mornings, saying no…", "Finds hard",
        vulnerable = true,
    ),
    FEARS(
        "FEARS", "What frightens you?",
        "Only you can see this unless you choose otherwise. Sharing it with your contacts can help them be gentle with you.",
        "Being alone in a crowd…", "Go gently with",
        vulnerable = true,
    ),
    ;

    /** The choices this topic allows. The vulnerable ones never reach everyone. */
    val choices: List<Pair<String, String>>
        get() = if (vulnerable) {
            listOf("CONTACTS" to "My contacts", "NOBODY" to "Only me")
        } else {
            listOf("EVERYONE" to "Everyone", "CONTACTS" to "My contacts", "NOBODY" to "Only me")
        }

    /** Who sees it before the person has said — matched to the server's default. */
    val defaultVisibility: String get() = if (vulnerable) "NOBODY" else "CONTACTS"

    companion object {
        fun of(key: String?): AboutYouTopic? = values().firstOrNull { it.key == key }
    }
}

private const val MAX_ENTRIES = 8
private const val MAX_ENTRY_LENGTH = 80

/**
 * Asked once, straight after the name and Viro ID.
 *
 * Every step can be skipped, and so can the whole thing: nothing here is
 * required to use Viro, and a person who wants to answer later finds all of
 * it again under You. Somebody's fears are not the price of an account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutYouSetupScreen(session: SessionManager, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val me by session.people.me.collectAsState()
    // The birthday first, then the topics in their deliberate order.
    val steps = remember { listOf<AboutYouTopic?>(null) + AboutYouTopic.values().toList() }
    var step by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val entries = remember { mutableStateMapOf<AboutYouTopic, List<String>>() }
    val visibilities = remember { mutableStateMapOf<AboutYouTopic, String>() }
    var birthDate by remember { mutableStateOf<LocalDate?>(null) }
    var birthdayVisibility by remember { mutableStateOf("NOBODY") }

    LaunchedEffect(me?.userId) {
        val current = me ?: session.people.refreshMe()
        for (answer in current?.aboutYou.orEmpty()) {
            val topic = AboutYouTopic.of(answer.topic)
            if (topic != null) {
                if (topic !in entries) entries[topic] = answer.entries
                answer.visibility?.let { visibilities.putIfAbsent(topic, it) }
            }
        }
        if (birthDate == null) birthDate = current?.birthDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        current?.birthdayVisibility?.let { birthdayVisibility = it }
    }

    val current = steps[step]
    val last = step == steps.lastIndex

    fun advance() {
        error = null
        if (last) onDone() else step += 1
    }

    fun saveThenAdvance() {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            val result = if (current == null) {
                session.people.update(
                    UpdateMeBody(
                        birthDate = birthDate?.toString() ?: "",
                        birthdayVisibility = birthdayVisibility,
                    ),
                ).map { }
            } else {
                session.people.setAboutYou(
                    current.key,
                    entries[current].orEmpty(),
                    visibilities[current] ?: current.defaultVisibility,
                ).map { }
            }
            saving = false
            result.onSuccess { advance() }.onFailure { error = it.message ?: "Couldn't save that. Try again." }
        }
    }

    ViroScreenBackground {
        ViroSafeScreen(applyImePadding = true, applyNavigationBarsPadding = true) {
            Column(Modifier.fillMaxSize().padding(horizontal = ViroSpacing.lg)) {
                Spacer(Modifier.height(ViroSpacing.sm))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { (step + 1).toFloat() / steps.size },
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = ViroColors.accent,
                        trackColor = ViroColors.surfaceRaised,
                    )
                    Spacer(Modifier.width(ViroSpacing.md))
                    // Always there, from the first screen. Nobody should have
                    // to answer six questions to find the way out.
                    TextButton(onClick = onDone, enabled = !saving) {
                        Text("Skip all", color = ViroColors.textSecondary)
                    }
                }

                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(ViroSpacing.lg))
                    if (current == null) {
                        BirthdayStep(
                            birthDate = birthDate,
                            onBirthDate = { birthDate = it },
                            visibility = birthdayVisibility,
                            onVisibility = { birthdayVisibility = it },
                        )
                    } else {
                        Text(
                            current.question,
                            style = MaterialTheme.typography.headlineMedium,
                            color = ViroColors.textPrimary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(current.hint, style = MaterialTheme.typography.bodyLarge, color = ViroColors.textSecondary)
                        Spacer(Modifier.height(ViroSpacing.lg))
                        TopicEditor(
                            topic = current,
                            entries = entries[current].orEmpty(),
                            onEntries = { entries[current] = it },
                            visibility = visibilities[current] ?: current.defaultVisibility,
                            onVisibility = { visibilities[current] = it },
                            enabled = !saving,
                        )
                    }
                    error?.let {
                        Spacer(Modifier.height(ViroSpacing.md))
                        ViroErrorMessage(it)
                    }
                    Spacer(Modifier.height(ViroSpacing.lg))
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { advance() }, enabled = !saving) {
                        Text(if (last) "Finish" else "Skip", color = ViroColors.textSecondary)
                    }
                    Spacer(Modifier.weight(1f))
                }
                ViroContinueButton(
                    text = when {
                        saving -> "Saving…"
                        last -> "Save and finish"
                        else -> "Next"
                    },
                    onClick = { saveThenAdvance() },
                    enabled = !saving,
                )
                Spacer(Modifier.height(ViroSpacing.lg))
            }
        }
    }
}

/**
 * The same editing, reached from You after setup — for anyone who skipped,
 * changed their mind, or joined before this existed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutYouEditor(session: SessionManager, onDone: () -> Unit) {
    AboutYouSetupScreen(session = session, onDone = onDone)
}

/**
 * A list of short lines with a box to add another. Each line is its own chip
 * so it can be taken back on its own, without editing the rest.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicEditor(
    topic: AboutYouTopic,
    entries: List<String>,
    onEntries: (List<String>) -> Unit,
    visibility: String,
    onVisibility: (String) -> Unit,
    enabled: Boolean,
) {
    var draft by remember(topic) { mutableStateOf("") }
    val full = entries.size >= MAX_ENTRIES

    fun add() {
        val text = draft.replace(Regex("\\s+"), " ").trim().take(MAX_ENTRY_LENGTH)
        if (text.isEmpty() || full) return
        if (entries.none { it.equals(text, ignoreCase = true) }) onEntries(entries + text)
        draft = ""
    }

    if (entries.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in entries) {
                Surface(shape = RoundedCornerShape(16.dp), color = ViroColors.surfaceRaised) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry, color = ViroColors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove $entry",
                            tint = ViroColors.textSecondary,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable(enabled = enabled) { onEntries(entries - entry) }
                                .padding(4.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(ViroSpacing.md))
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it.take(MAX_ENTRY_LENGTH) },
        placeholder = { Text(if (full) "That's the most you can add here." else topic.placeholder) },
        singleLine = true,
        enabled = enabled && !full,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { add() }),
        trailingIcon = {
            TextButton(onClick = { add() }, enabled = enabled && draft.isNotBlank() && !full) {
                Text("Add", color = ViroColors.accent)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(ViroSpacing.lg))
    Text("Who can see this", style = MaterialTheme.typography.titleSmall, color = ViroColors.textPrimary)
    Spacer(Modifier.height(8.dp))
    VisibilityChoice(topic.choices, visibility, onVisibility, enabled)
    if (topic.vulnerable) {
        Spacer(Modifier.height(6.dp))
        Text(
            // Said plainly, so the missing option is not mistaken for a bug.
            "This can be shared with your contacts, but never with everyone.",
            style = MaterialTheme.typography.bodySmall,
            color = ViroColors.textSecondary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VisibilityChoice(
    choices: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    enabled: Boolean,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in choices) {
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                enabled = enabled,
            )
        }
    }
}

/**
 * When somebody was born. Kept, never shown: at most the day and month, to the
 * people they choose. The year is how old they are, and that is theirs to say.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayStep(
    birthDate: LocalDate?,
    onBirthDate: (LocalDate?) -> Unit,
    visibility: String,
    onVisibility: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    Text("When were you born?", style = MaterialTheme.typography.headlineMedium, color = ViroColors.textPrimary)
    Spacer(Modifier.height(8.dp))
    Text(
        "Your date of birth stays private. You can choose to let people see your birthday — never the year.",
        style = MaterialTheme.typography.bodyLarge,
        color = ViroColors.textSecondary,
    )
    Spacer(Modifier.height(ViroSpacing.lg))

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ViroColors.surfaceRaised,
        modifier = Modifier.fillMaxWidth().clickable { picking = true },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                birthDate?.let { longDate(it) } ?: "Choose your date of birth",
                color = if (birthDate == null) ViroColors.textSecondary else ViroColors.textPrimary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (birthDate != null) {
                TextButton(onClick = { onBirthDate(null) }) { Text("Clear", color = ViroColors.textSecondary) }
            }
        }
    }

    Spacer(Modifier.height(ViroSpacing.lg))
    Text("Who can see your birthday", style = MaterialTheme.typography.titleSmall, color = ViroColors.textPrimary)
    Spacer(Modifier.height(8.dp))
    VisibilityChoice(
        listOf("EVERYONE" to "Everyone", "CONTACTS" to "My contacts", "NOBODY" to "Nobody"),
        visibility,
        onVisibility,
        enabled = birthDate != null,
    )

    if (picking) {
        val today = remember { LocalDate.now(ZoneOffset.UTC) }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = birthDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            yearRange = (today.year - 120)..today.year,
            selectableDates = object : SelectableDates {
                // Nothing in the future. The age floor is the server's to
                // enforce, with a reason it can explain.
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onBirthDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    picking = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

/**
 * What somebody has chosen to let this person know about them, on their page.
 *
 * Only what they have answered and allow. Nothing is shown for a question
 * they skipped — an empty "Go gently with" would read as though they had
 * nothing to fear, which is not what skipping means.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutYouSection(name: String, aboutYou: List<AboutYouTopicDto>, birthday: String?, modifier: Modifier = Modifier) {
    val shown = aboutYou
        .mapNotNull { answer -> AboutYouTopic.of(answer.topic)?.let { it to answer.entries } }
        .filter { (_, entries) -> entries.isNotEmpty() }
        .sortedBy { (topic, _) -> topic.ordinal }
    val day = birthday?.let { birthdayLabel(it) }

    if (shown.isNotEmpty() || day != null) {
        Column(modifier.fillMaxWidth()) {
            Text(
                "Getting to know ${name.substringBefore(' ')}",
                style = MaterialTheme.typography.titleMedium,
                color = ViroColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(ViroSpacing.sm))
            if (day != null) {
                Text("Birthday · $day", style = MaterialTheme.typography.bodyMedium, color = ViroColors.textSecondary)
                Spacer(Modifier.height(ViroSpacing.sm))
            }
            for ((topic, entries) in shown) {
                Spacer(Modifier.height(ViroSpacing.sm))
                Text(
                    topic.othersLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = ViroColors.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (entry in entries) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(ViroColors.surfaceRaised)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(entry, style = MaterialTheme.typography.bodyMedium, color = ViroColors.textPrimary)
                        }
                    }
                }
            }
        }
    }
}

/** "12 April" from "04-12", in the phone's own language. */
private fun birthdayLabel(mmdd: String): String? {
    val parts = mmdd.split('-')
    if (parts.size != 2) return null
    val month = parts[0].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = parts[1].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    return "$day " + Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault())
}

private fun longDate(date: LocalDate): String =
    "${date.dayOfMonth} " + date.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " ${date.year}"
