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
import com.viroreach.core.designsystem.components.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.CircleShape
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

    ViroSubScreen(title = "Media and storage", onBack = onBack) {
        ViroSection(
            title = "Download automatically",
            footer = "Voice notes and documents always wait until you tap them.",
        ) {
            ViroSwitchRow("Photos on mobile data", prefs.photosOnMobile, icon = Icons.Outlined.Image, onCheckedChange = {
                scope.launch { session.mediaSettings.set(AutoKind.PHOTO, metered = true, on = it) }
            })
            ViroSwitchRow("Photos on Wi-Fi", prefs.photosOnWifi, icon = Icons.Outlined.Image, onCheckedChange = {
                scope.launch { session.mediaSettings.set(AutoKind.PHOTO, metered = false, on = it) }
            })
            ViroSwitchRow("GIFs on mobile data", prefs.gifsOnMobile, icon = Icons.Outlined.Gif, onCheckedChange = {
                scope.launch { session.mediaSettings.set(AutoKind.GIF, metered = true, on = it) }
            })
            ViroSwitchRow("GIFs on Wi-Fi", prefs.gifsOnWifi, icon = Icons.Outlined.Gif, onCheckedChange = {
                scope.launch { session.mediaSettings.set(AutoKind.GIF, metered = false, on = it) }
            })
        }

        val u = usage
        ViroSection(
            title = "Storage",
            footer = "Messages stay. Photos and files download again when you open them.",
        ) {
            if (u == null) {
                ViroLoadingState(message = "Measuring what Viro keeps…")
            } else {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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
                    UsageLine("Photos", u.photoBytes, ViroColors.accent)
                    UsageLine("Voice notes", u.voiceBytes, ViroColors.success)
                    UsageLine("Documents", u.documentBytes, ViroColors.consumerWarning)
                    UsageLine("Other", u.otherBytes, ViroColors.textMuted)
                    if (u.outgoingBytes > 0) UsageLine("Waiting to send", u.outgoingBytes, ViroColors.textMuted)
                    lastFreed?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = ViroColors.success, fontSize = 13.sp)
                    }
                }
                ViroRowDivider(inset = false)
                ViroListRow(
                    if (busy) "Freeing up…" else "Free up space",
                    icon = Icons.Outlined.CleaningServices,
                    enabled = !busy && u.downloadedBytes > 0,
                    showChevron = false,
                    onClick = { confirmClearAll = true },
                )
            }
        }

        if (u != null && u.byConversation.isNotEmpty()) {
            ViroSection(title = "Chats using the most") {
                u.byConversation.take(10).forEach { row ->
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

/** One bar, in the same order as the lines below it. */
@Composable
private fun UsageBar(u: StorageUsage) {
    val total = u.totalBytes
    Row(
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(ViroColors.surfaceRaised),
    ) {
        Box(Modifier.weight(storageShare(u.photoBytes, total).coerceAtLeast(0.001f)).fillMaxHeight().background(ViroColors.accent))
        Box(Modifier.weight(storageShare(u.voiceBytes, total).coerceAtLeast(0.001f)).fillMaxHeight().background(ViroColors.success))
        Box(Modifier.weight(storageShare(u.documentBytes, total).coerceAtLeast(0.001f)).fillMaxHeight().background(ViroColors.consumerWarning))
        Box(Modifier.weight(storageShare(u.otherBytes + u.outgoingBytes, total).coerceAtLeast(0.001f)).fillMaxHeight().background(ViroColors.textMuted))
    }
}

@Composable
private fun UsageLine(label: String, bytes: Long, dot: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(label, color = ViroColors.textSecondary, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Text(formatStorageSize(bytes), color = ViroColors.textPrimary, fontSize = 14.sp)
    }
}

@Composable
private fun ConversationRow(row: ConversationUsage, enabled: Boolean, onClear: () -> Unit) {
    ViroListRow(
        title = row.title ?: "Chat",
        subtitle = "${formatStorageSize(row.bytes)} · ${row.files} ${if (row.files == 1) "file" else "files"}",
        icon = Icons.Outlined.ChatBubbleOutline,
        enabled = enabled,
        trailing = {
            TextButton(onClick = onClear, enabled = enabled) { Text("Clear", color = ViroColors.accent) }
        },
    )
}
