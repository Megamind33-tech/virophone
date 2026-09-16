package com.viroreach.app.consumer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                        "Devices",
                        style = MaterialTheme.typography.titleLarge,
                        color = ViroColors.textPrimary,
                    )
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
}

private fun formatDeviceTime(iso: String): String =
    runCatching {
        val ms = Instant.parse(iso).toEpochMilli()
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))
    }.getOrDefault(iso)
