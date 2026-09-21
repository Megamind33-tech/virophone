package com.viroreach.app.consumer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.viroreach.app.messaging.BackupState
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Chat backup: the only thing standing between a reinstall and losing every
 * encrypted conversation.
 *
 * The recovery key is shown once, here, and never again — this is the moment
 * the person has to be told plainly that nobody can recover it for them, so
 * the words do not soften it.
 */
@Composable
fun ChatBackupDialog(session: SessionManager, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<BackupState?>(null) }
    var newKey by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var typedKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        state = session.backup.state()
    }
    LaunchedEffect(Unit) { refresh() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (restoring) "Restore chats" else "Chat backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val shown = newKey
                when {
                    // Shown once, immediately after it is made.
                    shown != null -> {
                        Text(
                            "This is your recovery key. Write it down or save it somewhere " +
                                "safe — it is the only thing that can open your backup.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ViroColors.textSecondary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(shown, fontFamily = FontFamily.Monospace, color = ViroColors.textPrimary)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Viro does not have a copy. If you lose this key and then lose " +
                                "your phone, your chats cannot be brought back — not by us, " +
                                "not by anyone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ViroColors.consumerError,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { copyToClipboard(context, shown) }) { Text("Copy key") }
                    }
                    restoring -> {
                        Text(
                            "Type the recovery key from your old phone. Your chats come back " +
                                "as they were when it last backed up.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ViroColors.textSecondary,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = typedKey,
                            onValueChange = { typedKey = it },
                            label = { Text("Recovery key") },
                            singleLine = false,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        val current = state
                        Text(
                            "Your messages are end-to-end encrypted, which means this phone " +
                                "holds the only readable copy. A backup keeps them safe if you " +
                                "lose it — encrypted with a key only you have.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ViroColors.textSecondary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            when {
                                current == null -> "Checking…"
                                current.exists -> "Last backup ${whenItWas(current.updatedAt)} · " +
                                    "${current.messageCount} messages · ${sizeOf(current.sizeBytes)}"
                                current.on -> "Switched on. Nothing backed up yet."
                                else -> "Backup is off."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = ViroColors.textSecondary,
                        )
                    }
                }
                message?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ViroColors.textPrimary)
                }
            }
        },
        confirmButton = {
            val shown = newKey
            when {
                shown != null -> TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = "Backing up…"
                        scope.launch {
                            val result = session.backup.backupNow()
                            message = result.fold(
                                onSuccess = { "Backed up ${it.messageCount} messages." },
                                onFailure = { "Could not back up: ${it.message}" },
                            )
                            newKey = null
                            busy = false
                            refresh()
                        }
                    },
                ) { Text("I have written it down") }
                restoring -> TextButton(
                    enabled = !busy && typedKey.isNotBlank(),
                    onClick = {
                        busy = true
                        message = "Restoring…"
                        scope.launch {
                            val result = session.backup.restore(typedKey)
                            message = result.fold(
                                onSuccess = { "Restored $it messages." },
                                onFailure = { "Could not restore: ${it.message}" },
                            )
                            if (result.isSuccess) {
                                session.messaging.syncSoon()
                                restoring = false
                                typedKey = ""
                            }
                            busy = false
                            refresh()
                        }
                    },
                ) { Text("Restore") }
                state?.on == true -> TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = "Backing up…"
                        scope.launch {
                            val result = session.backup.backupNow()
                            message = result.fold(
                                onSuccess = { "Backed up ${it.messageCount} messages." },
                                onFailure = { "Could not back up: ${it.message}" },
                            )
                            busy = false
                            refresh()
                        }
                    },
                ) { Text("Back up now") }
                else -> TextButton(
                    enabled = !busy,
                    onClick = {
                        newKey = session.backup.turnOn()
                        message = null
                    },
                ) { Text("Turn on backup") }
            }
        },
        dismissButton = {
            when {
                newKey != null -> TextButton(enabled = !busy, onClick = { newKey = null }) { Text("Later") }
                restoring -> TextButton(enabled = !busy, onClick = { restoring = false; message = null }) { Text("Back") }
                state?.on == true -> TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            session.backup.turnOff()
                            message = "Backup is off, and the copy on the server is gone."
                            busy = false
                            refresh()
                        }
                    },
                ) { Text("Turn off", color = ViroColors.consumerError) }
                else -> TextButton(onClick = { restoring = true; message = null }) { Text("Restore") }
            }
        },
    )
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Viro recovery key", value))
    Toast.makeText(context, "Recovery key copied", Toast.LENGTH_SHORT).show()
}

private fun whenItWas(iso: String?): String = iso?.let {
    runCatching {
        DateTimeFormatter.ofPattern("d MMM, HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(it))
    }.getOrNull()
} ?: "recently"

private fun sizeOf(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes bytes"
}
