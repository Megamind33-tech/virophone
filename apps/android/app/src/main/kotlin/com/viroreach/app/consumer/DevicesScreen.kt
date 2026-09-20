package com.viroreach.app.consumer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroBackButton
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import com.viroreach.core.network.DeviceSummary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant

@Composable
fun DevicesScreen(
    session: SessionManager,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    var devices by remember { mutableStateOf<List<DeviceSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var linking by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var linkBusy by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var linked by remember { mutableStateOf<String?>(null) }
    val thisDeviceId = session.tokenStore.getDeviceId()
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        runCatching { session.api.listDevices() }
            .onSuccess { devices = it; error = null }
            .onFailure { error = "Couldn't load devices" }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(Modifier.fillMaxSize().padding(ViroSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViroBackButton(onClick = onBack)
                    Spacer(Modifier.width(ViroSpacing.sm))
                    Text(
                        "Linked devices",
                        style = MaterialTheme.typography.titleLarge,
                        color = ViroColors.textPrimary,
                    )
                }
                Spacer(Modifier.height(ViroSpacing.md))
                // Viro on a computer: the browser shows a code, and this is
                // where it is approved. Nothing is linked without this step.
                Column(Modifier.fillMaxWidth()) {
                    Button(onClick = { linking = true; code = ""; linkError = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("Link a device")
                    }
                    Text(
                        "Open " + com.viroreach.app.people.webAddress() + " on a computer, then enter the code it shows.",
                        color = ViroColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    linked?.let {
                        Text(
                            it + " is now linked.",
                            color = ViroColors.success,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(ViroSpacing.md))
                when {
                    loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ViroColors.accent)
                    }
                    error != null -> Text(error!!, color = ViroColors.textSecondary)
                    devices.isEmpty() -> Text(
                        "No active devices.",
                        color = ViroColors.textSecondary,
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(ViroSpacing.sm)) {
                        items(devices, key = { it.id }) { device ->
                            val isThis = device.id == thisDeviceId
                            Card(colors = CardDefaults.cardColors(containerColor = ViroColors.surface)) {
                                Column(Modifier.padding(ViroSpacing.md)) {
                                    Text(
                                        if (isThis) "This device" else device.platform,
                                        color = ViroColors.textPrimary,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        "${device.platform} · ${device.appVersion}",
                                        color = ViroColors.textSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Last seen ${formatDeviceTime(device.lastSeenAt)}",
                                        color = ViroColors.textMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    if (!isThis) {
                                        TextButton(onClick = {
                                            scope.launch {
                                                runCatching { session.api.revokeDevice(device.id) }
                                                reload()
                                            }
                                        }) {
                                            Text("Sign out device", color = ViroColors.consumerError)
                                        }
                                    } else {
                                        TextButton(onClick = {
                                            scope.launch {
                                                runCatching { session.api.revokeDevice(device.id) }
                                                session.logout()
                                                onLogout()
                                            }
                                        }) {
                                            Text("Sign out this device", color = ViroColors.accent)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (linking) {
        LinkDeviceDialog(
            code = code,
            busy = linkBusy,
            error = linkError,
            onCode = { code = it; linkError = null },
            onDismiss = { linking = false },
            onLink = {
                linkBusy = true
                linkError = null
                scope.launch {
                    runCatching { session.api.approveDeviceLink(com.viroreach.core.network.ApproveLinkBody(code)) }
                        .onSuccess {
                            linking = false
                            linked = it.label ?: "That device"
                            reload()
                        }
                        .onFailure {
                            linkError = com.viroreach.core.network.ApiDiagnostics
                                .parseFailure("POST", "devices/link/approve", it).message
                                ?: "That code did not work. Ask the computer for a new one."
                        }
                    linkBusy = false
                }
            },
        )
    }

}

private fun formatDeviceTime(iso: String): String =
    runCatching {
        val ms = Instant.parse(iso).toEpochMilli()
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))
    }.getOrDefault(iso)

/** Typing the code a browser is showing. */
@Composable
private fun LinkDeviceDialog(
    code: String,
    busy: Boolean,
    error: String?,
    onCode: (String) -> Unit,
    onDismiss: () -> Unit,
    onLink: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link a device") },
        text = {
            Column {
                Text(
                    "On the computer, open " + com.viroreach.app.people.webAddress() +
                        ". Type the code it shows here.",
                    color = ViroColors.textSecondary,
                )
                Spacer(Modifier.height(ViroSpacing.md))
                OutlinedTextField(
                    value = code,
                    onValueChange = { onCode(it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8)) },
                    label = { Text("8-character code") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Whoever holds that code can read and send your messages from that computer, " +
                        "until you remove it here.",
                    color = ViroColors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onLink, enabled = !busy && code.length == 8) {
                Text(if (busy) "Linking…" else "Link")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
