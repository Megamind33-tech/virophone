package com.viroreach.app.consumer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.app.messaging.AutoKind
import com.viroreach.app.messaging.ConversationUsage
import com.viroreach.app.messaging.MediaPreferences
import com.viroreach.app.messaging.StorageUsage
import com.viroreach.app.messaging.formatStorageSize
import com.viroreach.app.messaging.storageShare
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroBackButton
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground
import kotlinx.coroutines.launch

/**
 * What arrives by itself, and what Viro is keeping. Data costs money here, so
 * both are the person's choice rather than something they discover on a bill.
 */
@Composable
fun MediaStorageScreen(session: SessionManager, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val prefs by session.mediaSettings.preferences.collectAsState(initial = MediaPreferences())
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var lastFreed by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        usage = session.mediaStorage.usage()
    }
    LaunchedEffect(Unit) { reload() }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ViroSpacing.sm, vertical = ViroSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViroBackButton(onClick = onBack)
                    Spacer(Modifier.width(ViroSpacing.sm))
                    Text("Media and storage", style = MaterialTheme.typography.titleLarge, color = ViroColors.textPrimary)
                }

                LazyColumn(Modifier.fillMaxSize().padding(horizontal = ViroSpacing.md)) {
                    item {
                        SectionTitle("Download automatically")
                        Text(
                            "Voice notes and documents always wait until you tap them.",
                            color = ViroColors.textMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        AutoRow("Photos on mobile data", prefs.photosOnMobile) {
                            scope.launch { session.mediaSettings.set(AutoKind.PHOTO, metered = true, on = it) }
                        }
                        AutoRow("Photos on Wi-Fi", prefs.photosOnWifi) {
                            scope.launch { session.mediaSettings.set(AutoKind.PHOTO, metered = false, on = it) }
                        }
                        AutoRow("GIFs on mobile data", prefs.gifsOnMobile) {
                            scope.launch { session.mediaSettings.set(AutoKind.GIF, metered = true, on = it) }
                        }
                        AutoRow("GIFs on Wi-Fi", prefs.gifsOnWifi) {
                            scope.launch { session.mediaSettings.set(AutoKind.GIF, metered = false, on = it) }
                        }
                        Spacer(Modifier.height(ViroSpacing.lg))
                        SectionTitle("Storage")
                    }

                    val u = usage
                    if (u == null) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(ViroSpacing.lg), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = ViroColors.accent)
                            }
                        }
                    } else {
                        item {
                            Text(
                                formatStorageSize(u.totalBytes),
                                color = ViroColors.textPrimary,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("used by Viro on this phone", color = ViroColors.textSecondary, fontSize = 13.sp)
                            Spacer(Modifier.height(ViroSpacing.md))
                            UsageBar(u)
                            Spacer(Modifier.height(ViroSpacing.sm))
                            UsageLine("Photos", u.photoBytes)
                            UsageLine("Voice notes", u.voiceBytes)
                            UsageLine("Documents", u.documentBytes)
                            UsageLine("Other", u.otherBytes)
                            if (u.outgoingBytes > 0) UsageLine("Waiting to send", u.outgoingBytes)
                            lastFreed?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, color = ViroColors.success, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(ViroSpacing.md))
                            Button(
                                onClick = { confirmClearAll = true },
                                enabled = !busy && u.downloadedBytes > 0,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (busy) "Freeing up…" else "Free up space") }
                            Text(
                                "Messages stay. Photos and files download again when you open them.",
                                color = ViroColors.textMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            if (u.byConversation.isNotEmpty()) {
                                Spacer(Modifier.height(ViroSpacing.lg))
                                SectionTitle("Chats using the most")
                            }
                        }
                        items(u.byConversation.take(10), key = { it.conversationId }) { row ->
                            ConversationRow(row, enabled = !busy) {
                                busy = true
                                scope.launch {
                                    val freed = session.mediaStorage.clearConversation(row.conversationId)
                                    lastFreed = "Freed ${formatStorageSize(freed)}"
                                    reload()
                                    busy = false
                                }
                            }
                        }
                        item { Spacer(Modifier.height(ViroSpacing.xl)) }
                    }
                }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Free up space?") },
            text = {
                Text(
                    "Photos, voice notes and documents already downloaded are removed from this phone. " +
                        "Your messages stay, and anything you open downloads again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    busy = true
                    scope.launch {
                        val freed = session.mediaStorage.clearDownloads()
                        lastFreed = "Freed ${formatStorageSize(freed)}"
                        reload()
                        busy = false
                    }
                }) { Text("Free up space") }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = ViroColors.textMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
}

@Composable
private fun AutoRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = ViroColors.textPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One bar, in the same order as the lines below it. */
@Composable
private fun UsageBar(u: StorageUsage) {
    val total = u.totalBytes
    Row(
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(ViroColors.surfaceRaised),
    ) {
        Box(Modifier.weight(storageShare(u.photoBytes, total).coerceAtLeast(0.001f)).fillMaxHeight().background(ViroColors.accent))
        Box(Modifier.weight(storageShare(u.voiceBytes, total).coerceAtLeast(0.001f)).fillMaxHeight().background(ViroColors.success))
        Box(Modifier.weight(storageShare(u.documentBytes, total).coerceAtLeast(0.001f)).fillMaxHeight().background(Color(0xFFFFD180)))
        Box(Modifier.weight(storageShare(u.otherBytes + u.outgoingBytes, total).coerceAtLeast(0.001f)).fillMaxHeight().background(ViroColors.textMuted))
    }
}

@Composable
private fun UsageLine(label: String, bytes: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = ViroColors.textSecondary, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Text(formatStorageSize(bytes), color = ViroColors.textPrimary, fontSize = 14.sp)
    }
}

@Composable
private fun ConversationRow(row: ConversationUsage, enabled: Boolean, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClear).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.title ?: "Chat", color = ViroColors.textPrimary, fontSize = 15.sp)
            Text(
                "${formatStorageSize(row.bytes)} · ${row.files} ${if (row.files == 1) "file" else "files"}",
                color = ViroColors.textSecondary,
                fontSize = 12.sp,
            )
        }
        Text("Clear", color = ViroColors.accent, fontSize = 14.sp)
    }
}
