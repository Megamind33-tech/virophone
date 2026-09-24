package com.viroreach.app.people

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroCallBrand
import com.viroreach.core.designsystem.components.ViroContinueButton
import com.viroreach.core.designsystem.components.ViroErrorMessage
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Asked once, after signing in: the name other people see, and a Viro ID so
 * they can reach this person without a phone number. Phone sign-ups used to
 * get an empty name and email sign-ups the part before the @.
 */
@Composable
fun ProfileSetupScreen(session: SessionManager, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val me by session.people.me.collectAsState()

    var name by rememberSaveable { mutableStateOf("") }
    var handle by rememberSaveable { mutableStateOf("") }
    var handleEdited by rememberSaveable { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var reason by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Prefill from whatever the account already has; an email local part is a
    // placeholder the server invented, not a name they chose.
    LaunchedEffect(me?.userId) {
        val current = me ?: session.people.refreshMe()
        if (name.isBlank()) name = current?.displayName.orEmpty()
        if (handle.isBlank()) current?.viroId?.removePrefix("@")?.let { handle = it; handleEdited = true }
    }

    // While the Viro ID is untouched it follows the name.
    LaunchedEffect(name, handleEdited) {
        if (handleEdited || name.isBlank()) return@LaunchedEffect
        delay(500)
        val check = session.people.checkViroId(null, name) ?: return@LaunchedEffect
        if (!handleEdited) check.suggestions?.firstOrNull()?.let { handle = it.removePrefix("@") }
    }

    LaunchedEffect(handle) {
        val h = handle.trim()
        if (h.isBlank()) {
            available = null; reason = null; suggestions = emptyList(); return@LaunchedEffect
        }
        delay(400)
        checking = true
        val check = session.people.checkViroId(h, name)
        checking = false
        if (check != null) {
            available = check.valid && check.available
            reason = check.reason
            suggestions = check.suggestions.orEmpty().filter { it.removePrefix("@") != h }
        }
    }

    fun save() {
        val n = name.trim()
        val h = handle.trim()
        if (n.isBlank() || h.isBlank() || saving) return
        saving = true
        error = null
        scope.launch {
            session.people.completeProfile(n, h)
                .onSuccess { saving = false; onDone() }
                .onFailure { saving = false; error = it.message }
        }
    }

    ViroScreenBackground {
        ViroSafeScreen(applyImePadding = true, applyNavigationBarsPadding = true) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = ViroSpacing.lg),
            ) {
                Spacer(Modifier.height(ViroSpacing.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ViroCallBrand() }
                Spacer(Modifier.height(ViroSpacing.xl))
                Text("What's your name?", style = MaterialTheme.typography.headlineMedium, color = ViroColors.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "This is how you appear to the people you call and message.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(ViroSpacing.lg))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 60) name = it },
                    label = { Text("Your name") },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(ViroSpacing.lg))
                Text("Your Viro ID", style = MaterialTheme.typography.titleMedium, color = ViroColors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "People can find you by this without knowing your phone number — useful if you signed up with an email address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = handle,
                    onValueChange = {
                        handleEdited = true
                        handle = it.lowercase().removePrefix("@").take(30)
                    },
                    label = { Text("Viro ID") },
                    prefix = { Text("@", color = ViroColors.textSecondary) },
                    singleLine = true,
                    enabled = !saving,
                    isError = available == false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    supportingText = {
                        when {
                            checking -> Text("Checking…", color = ViroColors.textSecondary)
                            available == true -> Text("@$handle is free", color = ViroColors.accent)
                            reason != null -> Text(reason!!, color = MaterialTheme.colorScheme.error)
                            else -> Text("Letters, numbers, dots and underscores.", color = ViroColors.textSecondary)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (available != true && suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Try", style = MaterialTheme.typography.labelMedium, color = ViroColors.textMuted)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.take(3).forEach { s ->
                            SuggestionChip(
                                onClick = { handleEdited = true; handle = s.removePrefix("@") },
                                label = { Text(s) },
                            )
                        }
                    }
                }

                error?.let {
                    Spacer(Modifier.height(ViroSpacing.md))
                    ViroErrorMessage(it)
                }

                Spacer(Modifier.height(ViroSpacing.lg))
                ViroContinueButton(
                    text = if (saving) "Saving…" else "Continue",
                    onClick = { save() },
                    enabled = !saving && name.isNotBlank() && available == true,
                )
                Spacer(Modifier.height(ViroSpacing.lg))
            }
        }
    }
}

/** The card on You: my Viro ID, how people reach me, and sharing it. */
@Composable
fun MyViroIdSummary(viroId: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(viroId ?: "No Viro ID yet", color = ViroColors.textPrimary, fontWeight = FontWeight.SemiBold)
        Text(
            "People can find you by this without your phone number.",
            style = MaterialTheme.typography.bodySmall,
            color = ViroColors.textSecondary,
        )
    }
}
