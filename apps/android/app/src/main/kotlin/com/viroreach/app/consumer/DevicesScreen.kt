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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import com.viroreach.core.designsystem.components.*
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

    val signOut: (DeviceSummary, Boolean) -> Unit = { device, isThis ->
        scope.launch {
            runCatching { session.api.revokeDevice(device.id) }
            if (isThis) {
                session.logout()
                onLogout()
            } else {
                reload()
            }
        }
    }

    ViroSubScreen(title = "Linked devices", onBack = onBack) {
        // Viro on a computer: the browser shows a code, and this is where it
        // is approved. Nothing is linked without this step.
        ViroSection(
            footer = "Open " + com.viroreach.app.people.webAddress() + " on a computer, then enter the code it shows.",
        ) {
            ViroListRow(
                "Link a device",
                icon = Icons.Outlined.QrCodeScanner,
                onClick = { linking = true; code = ""; linkError = null },
            )
        }
        linked?.let { ViroStatusLine("$it is now linked.", positive = true) }

        when {
            loading -> ViroLoadingState()
            error != null -> ViroMessageState(
                title = "Devices couldn't load",
                body = "Check your connection and try again.",
                actionLabel = "Try again",
                onAction = { scope.launch { reload() } },
            )
            devices.isEmpty() -> ViroMessageState(title = "No active devices", icon = Icons.Outlined.Devices)
            else -> {
                val (current, others) = devices.partition { it.id == thisDeviceId }
                current.firstOrNull()?.let { device ->
                    ViroSection(title = "This device") {
                        DeviceRow(device, isThis = true)
                        ViroRowDivider()
                        ViroListRow(
                            "Sign out of this device",
                            icon = Icons.AutoMirrored.Outlined.Logout,
                            destructive = true,
                            showChevron = false,
                            onClick = { signOut(device, true) },
                        )
                    }
                }
                if (others.isNotEmpty()) {
                    ViroSection(
                        title = "Other devices",
                        footer = "Signing a device out removes it straight away; it will need to be linked again.",
                    ) {
                        others.forEachIndexed { index, device ->
                            if (index > 0) ViroRowDivider()
                            DeviceRow(device, isThis = false, onSignOut = { signOut(device, false) })
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

@Composable
private fun DeviceRow(device: DeviceSummary, isThis: Boolean, onSignOut: (() -> Unit)? = null) {
    val web = device.platform.contains("web", ignoreCase = true)
    ViroListRow(
        title = if (isThis) "This phone" else device.platform.replaceFirstChar { it.uppercase() },
        subtitle = "${device.platform} · ${device.appVersion}\nLast active ${formatDeviceTime(device.lastSeenAt)}",
        icon = if (web) Icons.Outlined.Computer else Icons.Outlined.PhoneAndroid,
        trailing = onSignOut?.let {
            { TextButton(onClick = it) { Text("Sign out", color = ViroColors.consumerError) } }
        },
    )
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
