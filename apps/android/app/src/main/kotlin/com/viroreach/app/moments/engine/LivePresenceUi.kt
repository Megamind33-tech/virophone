package com.viroreach.app.moments.engine

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import com.viroreach.core.designsystem.ViroColors
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.viroreach.core.designsystem.components.ViroAvatar
import com.viroreach.core.designsystem.components.ViroAvatarSize
import com.viroreach.core.network.MomentParticipantDto
import com.viroreach.voice.webrtc.MomentPresenceEngine
import com.viroreach.voice.webrtc.PresencePeer
import com.viroreach.voice.webrtc.PresenceQuality
import io.livekit.android.renderer.TextureViewRenderer

/**
 * Draws someone's camera. `null` identity is this phone's own.
 *
 * Only the room's engine can attach video, so modules are handed this rather
 * than the engine itself: they can show a face, not start or stop anything.
 */
class MomentVideo(private val engine: MomentPresenceEngine) {
    @Composable
    fun Show(identity: String?, modifier: Modifier = Modifier) {
        // A new view per person, so a renderer never shows the wrong face
        // while it is being handed to someone else.
        key(identity) {
            AndroidView(
                factory = { context -> TextureViewRenderer(context) },
                modifier = modifier,
                update = { renderer -> engine.attachVideo(identity, renderer) },
                onRelease = { renderer -> engine.releaseRenderer(renderer) },
            )
        }
    }
}

/** One person, as the room sees them now. Names come from the Moment, never from the media server. */
internal data class LiveFace(
    val participant: MomentParticipantDto?,
    val peer: PresencePeer?,
    val isMe: Boolean,
) {
    val name: String get() = participant?.displayName?.trim()?.substringBefore(' ')?.ifBlank { null } ?: if (isMe) "You" else "Someone"
    val identity: String? get() = if (isMe) null else peer?.identity
}

/** True when anyone in the room is showing their camera — then the room is faces on video. */
internal fun MomentRoomContext.anyVideo(): Boolean =
    media.cameraOn || media.peers.any { it.cameraOn }

/**
 * Faces, live. The other person large when there are two of you; a gathering
 * of tiles when there are more. Your own camera is a small window you can flip.
 */
@Composable
internal fun LiveStage(ctx: MomentRoomContext, modifier: Modifier = Modifier) {
    val video = ctx.video
    if (video != null) LiveStageWith(ctx, video, modifier)
}

@Composable
private fun LiveStageWith(ctx: MomentRoomContext, video: MomentVideo, modifier: Modifier) {
    val others = ctx.others.map { p -> LiveFace(p, ctx.media.peers.firstOrNull { it.identity == p.userId }, isMe = false) }
    Box(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (others.size <= 1) {
            val other = others.firstOrNull()
            if (other != null) {
                LiveTile(other, video, Modifier.fillMaxSize(), large = true)
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("The door is open", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text("Whoever joins will see you here.", color = Color.White.copy(alpha = 0.7f))
                }
            }
        } else {
            val shown = others.take(MAX_TILES)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { face -> LiveTile(face, video, Modifier.weight(1f).fillMaxSize(), large = false) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (others.size > shown.size) {
                    Text(
                        "and ${others.size - shown.size} more people here",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
        if (ctx.media.cameraOn) {
            // Your own window: small, in the corner, flippable to show what's in front of you.
            Box(
                Modifier.align(Alignment.BottomEnd).padding(12.dp).width(104.dp).aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(16.dp)).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
            ) {
                video.Show(null, Modifier.fillMaxSize())
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(32.dp).clickable(onClick = ctx.flipCamera),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Cameraswitch, "Switch camera", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTile(face: LiveFace, video: MomentVideo, modifier: Modifier, large: Boolean) {
    val peer = face.peer
    val speaking = peer?.speaking == true
    // Somebody talking should be visible from across the room, not a hairline
    // that appears and vanishes. The ring swells while they hold the floor and
    // settles when they stop, so a glance tells you who is speaking.
    val ring by animateDpAsState(
        if (speaking) (if (large) 3.dp else 2.5.dp) else 0.dp,
        tween(160), label = "speakRing",
    )
    val glow by animateFloatAsState(if (speaking) 1f else 0f, tween(220), label = "speakGlow")
    val pulse = rememberInfiniteTransition(label = "speakPulse")
    val beat by pulse.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(680, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "speakBeat",
    )
    val corner = RoundedCornerShape(if (large) 24.dp else 18.dp)
    Box(
        modifier.clip(corner).border(
            ring,
            ViroColors.BlueAccent.copy(alpha = (0.55f + beat * 0.45f) * glow),
            corner,
        ),
    ) {
        Surface(color = Color.Black.copy(alpha = 0.25f), modifier = Modifier.fillMaxSize()) {}
        if (peer != null && peer.cameraOn && !peer.videoPaused) {
            video.Show(face.identity, Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                ViroAvatar(size = if (large) ViroAvatarSize.Hero else ViroAvatarSize.Large, displayName = face.participant?.displayName ?: "")
                if (peer?.cameraOn == true && peer.videoPaused) {
                    Spacer(Modifier.height(10.dp))
                    Text("Video paused — weak connection", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.45f)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(face.name, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Three bars keeping time. Colour alone would leave this
                    // out for anyone who cannot see the ring.
                    if (speaking) {
                        Spacer(Modifier.width(7.dp))
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (i in 0..2) {
                                val h = 5.dp + (7.dp * ((beat + i * 0.28f) % 1f))
                                Box(
                                    Modifier.width(2.5.dp).height(h)
                                        .clip(RoundedCornerShape(50))
                                        .background(ViroColors.BlueAccent),
                                )
                            }
                        }
                    }
                    if (peer != null && !peer.micOn) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.MicOff, "Microphone off", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
                    }
                }
            }
            if (peer?.quality == PresenceQuality.WEAK || peer?.quality == PresenceQuality.LOST) {
                Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.45f)) {
                    Text("Weak connection", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

private const val MAX_TILES = 6
