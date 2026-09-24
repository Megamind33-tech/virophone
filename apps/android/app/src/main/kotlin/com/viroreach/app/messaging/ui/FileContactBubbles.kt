package com.viroreach.app.messaging.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.app.messaging.ChatMessage
import com.viroreach.app.messaging.ContactCard
import com.viroreach.core.designsystem.ViroColors
import java.util.Locale

/** 1.2 MB, 340 KB — the size as a person reads it. */
fun formatFileSize(bytes: Long?): String {
    val b = bytes ?: return ""
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return "${kb.toInt()} KB"
    val mb = kb / 1024.0
    return String.format(Locale.US, if (mb < 10) "%.1f MB" else "%.0f MB", mb)
}

/** The word for a document's kind, from its name: PDF, Word, Sheet… */
fun fileKindLabel(name: String?, mime: String?): String {
    val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return when {
        ext == "pdf" || mime == "application/pdf" -> "PDF"
        ext in setOf("doc", "docx", "odt", "rtf") -> "Document"
        ext in setOf("xls", "xlsx", "ods", "csv") -> "Spreadsheet"
        ext in setOf("ppt", "pptx", "odp") -> "Presentation"
        ext in setOf("zip") -> "Archive"
        ext in setOf("txt") -> "Text"
        mime?.startsWith("video/") == true -> "Video"
        mime?.startsWith("audio/") == true -> "Audio"
        mime?.startsWith("image/") == true -> "Image"
        ext.isNotBlank() -> ext.uppercase()
        else -> "File"
    }
}

/** A document in a chat: name, kind and size; tap to open it. */
@Composable
fun FileContent(msg: ChatMessage, downloading: Boolean, onOpen: (ChatMessage) -> Unit) {
    val name = msg.fileName ?: "Document"
    Row(
        Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .clickable(enabled = !msg.isPending) { onOpen(msg) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            if (downloading) CircularProgressIndicator(Modifier.size(20.dp), color = ViroColors.accent, strokeWidth = 2.dp)
            else Icon(Icons.Default.Description, null, tint = ViroColors.accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    fileKindLabel(name, msg.media?.mime),
                    formatFileSize(msg.fileSize).takeIf { it.isNotBlank() },
                    if (msg.isPending) "Sending…" else null,
                ).joinToString(" · "),
                color = ViroColors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
    msg.body?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, color = Color.White, fontSize = 15.sp)
    }
}

/** A contact someone shared: their name, and what you can do with it. */
@Composable
fun ContactContent(
    card: ContactCard,
    onMessage: (ContactCard) -> Unit,
    onCall: (ContactCard) -> Unit,
    onSave: (ContactCard) -> Unit,
) {
    Column(Modifier.widthIn(max = 268.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(memberColor(card.name).copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    card.name.split(' ').filter { it.isNotBlank() }.take(2)
                        .joinToString("") { it.first().uppercase() }.ifBlank { "#" },
                    color = ViroColors.background,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(card.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = card.phones.firstOrNull() ?: card.viroId
                line?.let { Text(it, color = ViroColors.textSecondary, fontSize = 13.sp) }
                if (card.phones.size > 1) {
                    Text("+${card.phones.size - 1} more number${if (card.phones.size > 2) "s" else ""}", color = ViroColors.textMuted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            // Messaging and calling need them to be on Viro; saving always works.
            if (card.userId != null) {
                TextButton(onClick = { onMessage(card) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Chat, null, tint = ViroColors.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Message", color = ViroColors.accent, fontSize = 13.sp)
                }
                TextButton(onClick = { onCall(card) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Call, null, tint = ViroColors.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Call", color = ViroColors.accent, fontSize = 13.sp)
                }
            }
            if (card.phones.isNotEmpty()) {
                TextButton(onClick = { onSave(card) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.PersonAdd, null, tint = ViroColors.textSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save", color = ViroColors.textSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}
