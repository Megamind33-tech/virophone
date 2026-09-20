package com.viroreach.app.messaging.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.app.messaging.ChatMessage
import com.viroreach.app.messaging.SharedPlace
import com.viroreach.app.messaging.location.ViroLocation
import com.viroreach.core.designsystem.ViroColors
import kotlinx.coroutines.delay

/** "4 min left", "1 h 12 min left" — how long a live share still has to run. */
fun liveRemaining(untilMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val left = untilMs - nowMs
    if (left <= 0) return "Ended"
    val minutes = (left / 60_000).toInt()
    return when {
        minutes < 1 -> "Less than a minute left"
        minutes < 60 -> "$minutes min left"
        else -> "${minutes / 60} h ${minutes % 60} min left"
    }
}

/** "just now", "2 min ago" — when a live location last moved. */
fun lastMoved(updatedAtMs: Long?, nowMs: Long = System.currentTimeMillis()): String? {
    val at = updatedAtMs ?: return null
    val minutes = ((nowMs - at) / 60_000).toInt()
    return when {
        minutes <= 0 -> "Updated just now"
        minutes == 1 -> "Updated a minute ago"
        minutes < 60 -> "Updated $minutes min ago"
        else -> "Updated ${minutes / 60} h ago"
    }
}

/**
 * A place in a chat: tap to open it in a maps app. A live share also shows how
 * long it has left, and — for the person sharing — a way to stop.
 */
@Composable
fun LocationContent(
    msg: ChatMessage,
    place: SharedPlace,
    onOpen: (SharedPlace) -> Unit,
    onStopSharing: (ChatMessage) -> Unit,
) {
    // The countdown ticks on its own while the share runs.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(place.liveUntil) {
        while (place.liveUntil != null && place.liveUntil > System.currentTimeMillis()) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
        now = System.currentTimeMillis()
    }
    val live = place.liveUntil != null && place.liveUntil > now

    Column(Modifier.widthIn(max = 260.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.07f))
                .clickable { onOpen(place) }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(if (live) ViroColors.accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.LocationOn, null, tint = if (live) ViroColors.accent else ViroColors.textSecondary)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        live -> "Live location"
                        place.hasEnded -> "Live location ended"
                        else -> place.label?.takeIf { it.isNotBlank() } ?: "Location"
                    },
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        live -> listOfNotNull(liveRemaining(place.liveUntil!!, now), lastMoved(place.updatedAt, now)).joinToString(" · ")
                        else -> place.label?.let { ViroLocation.format(place.lat, place.lng) }
                            ?: ViroLocation.format(place.lat, place.lng)
                    },
                    color = ViroColors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onOpen(place) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Open in Maps", color = ViroColors.accent, fontSize = 13.sp)
            }
            if (live && msg.mine) {
                TextButton(onClick = { onStopSharing(msg) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Stop sharing", color = ViroColors.consumerError, fontSize = 13.sp)
                }
            }
        }
    }
}
