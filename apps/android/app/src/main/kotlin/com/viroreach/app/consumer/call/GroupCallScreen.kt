package com.viroreach.app.consumer.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.viroreach.app.consumer.data.ConferenceParticipant
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import kotlinx.coroutines.delay

@Composable
fun GroupCallScreen(
    session: SessionManager,
    onEndCall: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val conference = session.conferenceManager
    val state by conference.state.collectAsState()

    LaunchedEffect(state.isActive) {
        while (state.isActive) {
            delay(1000)
            conference.tickElapsed()
        }
    }

    val activeSpeaker = state.participants.firstOrNull { it.isActiveSpeaker }
        ?: state.participants.firstOrNull()

    ViroScreenBackground {
        ViroSafeScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ViroSpacing.md),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ViroBackButton(onClick = onEndCall)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("Viro Call", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Group Call", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(ViroColors.GreenAvailable, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(formatElapsed(state.elapsedSeconds), color = ViroColors.GreenAvailable)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color.White)
                    Text("${state.participants.size}", color = Color.White)
                }
            }
            Spacer(Modifier.height(16.dp))
            activeSpeaker?.let { speaker ->
                ActiveSpeakerCard(speaker)
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.participants.filter { !it.isActiveSpeaker }) { participant ->
                    ParticipantTile(participant) {
                        conference.setActiveSpeaker(participant.id)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            ViroCallControlGrid(
                controls = listOf(
                    ViroCallControl("mute", "Mute", Icons.Default.Mic, Icons.Default.MicOff),
                    ViroCallControl("video", "Video", Icons.Default.Videocam),
                    ViroCallControl("speaker", "Speaker", Icons.Default.VolumeUp, Icons.Default.VolumeOff),
                    ViroCallControl("add", "Add people", Icons.Default.PersonAdd),
                    ViroCallControl("chat", "Chat", Icons.Default.Chat),
                    ViroCallControl("more", "More", Icons.Default.MoreVert),
                ),
                onControl = { id ->
                    when (id) {
                        "mute" -> conference.toggleMute("local")
                        "chat" -> onOpenChat()
                        else -> Unit
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ViroEndCallButton(
                    onClick = {
                        conference.endConference()
                        onEndCall()
                    },
                )
            }
        }
        }
    }
}

@Composable
private fun ActiveSpeakerCard(participant: ConferenceParticipant) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .border(2.dp, ViroColors.ElectricBlueGlow, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ViroColors.NavySurfaceElevated),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ViroAvatar(modifier = Modifier.size(80.dp), size = ViroAvatarSize.Large)
            Text(participant.name, color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                if (participant.speaking) "Speaking…" else "Listening…",
                color = ViroColors.ElectricBlue,
            )
            AudioVisualizerBars()
        }
    }
}

@Composable
private fun ParticipantTile(participant: ConferenceParticipant, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.size(width = 100.dp, height = 120.dp),
        colors = CardDefaults.cardColors(containerColor = ViroColors.NavySurfaceElevated),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ViroAvatar(modifier = Modifier.size(48.dp), size = ViroAvatarSize.Medium)
                Text(participant.name, color = Color.White, style = MaterialTheme.typography.labelMedium)
                Text("Listening…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            if (participant.muted) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = ViroColors.RedEndCall,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun AudioVisualizerBars() {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(8, 16, 12, 16, 8).forEach { h ->
            Box(
                Modifier
                    .width(4.dp)
                    .height(h.dp)
                    .background(ViroColors.ElectricBlue, RoundedCornerShape(2.dp)),
            )
        }
    }
}

private fun formatElapsed(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private val CircleShape = androidx.compose.foundation.shape.CircleShape
