package com.viroreach.app.moments.engine

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.core.designsystem.components.ViroAvatar
import com.viroreach.core.designsystem.components.ViroAvatarSize
import com.viroreach.core.network.MomentParticipantDto
import java.time.Instant

/** How long two people have been in the room together, the way a person would say it. */
internal fun togetherFor(ctx: MomentRoomContext, other: MomentParticipantDto? = null): String? {
    val mine = ctx.participants.firstOrNull { it.userId == ctx.me }?.joinedAt?.let(::millisOf) ?: return null
    val theirs = (other ?: ctx.others.firstOrNull())?.joinedAt?.let(::millisOf) ?: return null
    val minutes = ((ctx.now - maxOf(mine, theirs)).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min together"
        else -> "${minutes / 60} h ${minutes % 60} min together"
    }
}

private fun millisOf(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

/** Whether someone can be heard right now, in words: "Talking", "Can hear you". */
private fun voiceOf(ctx: MomentRoomContext, p: MomentParticipantDto): String? {
    val peer = ctx.media.peers.firstOrNull { it.identity == p.userId } ?: return null
    return when {
        peer.speaking -> "Talking"
        peer.micOn -> "Microphone on"
        else -> null
    }
}

private fun firstName(p: MomentParticipantDto): String = p.displayName.trim().substringBefore(' ').ifBlank { "Someone" }

/**
 * The people are the room.
 *
 * For "be with me", for talking, and — until the camera arrives in the room —
 * for everything that is about who is here rather than what is on a screen.
 * Faces, large; names; how long you have had each other. No list, no counts
 * for a room this size.
 */
object PresenceModule : MomentModule {
    override val key = "PRESENCE"

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Primary(ctx: MomentRoomContext, modifier: Modifier) {
        if (ctx.video != null && ctx.anyVideo()) {
            // Someone has chosen to show themselves: the room becomes faces.
            LiveStage(ctx, modifier)
        } else {
            Gathered(ctx, modifier)
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Gathered(ctx: MomentRoomContext, modifier: Modifier) {
        val others = ctx.others
        Column(
            modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (others.isEmpty()) {
                // The host, alone for now. Not an empty screen: an open door.
                val me = ctx.participants.firstOrNull { it.userId == ctx.me }
                ViroAvatar(size = ViroAvatarSize.Hero, displayName = me?.displayName ?: "")
                Spacer(Modifier.height(20.dp))
                Text("The door is open", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Whoever joins will be right here with you.",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            } else if (others.size == 1) {
                val other = others.first()
                ViroAvatar(size = ViroAvatarSize.Hero, displayName = other.displayName)
                Spacer(Modifier.height(20.dp))
                Text(
                    other.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text("is here with you", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyLarge)
                voiceOf(ctx, other)?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
                }
                togetherFor(ctx, other)?.let {
                    Spacer(Modifier.height(18.dp))
                    Text(it, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                // A few people: gathered, not gridded. The most recent arrivals
                // are last, so the room fills the way a room does.
                val shown = others.take(MAX_FACES)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    shown.forEach { p ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
                            ViroAvatar(size = ViroAvatarSize.Large, displayName = p.displayName)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                firstName(p),
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                // People, not a number — the number only for what does not fit.
                val rest = others.size - shown.size
                Text(
                    if (rest > 0) "and $rest more people here" else "All here together",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    private const val MAX_FACES = 8
}

/**
 * Just being there.
 *
 * Calm, almost still, and nothing asking to be pressed. The room does not
 * prompt anyone to say something: silence is allowed, and is the point.
 */
object QuietModule : MomentModule {
    override val key = "QUIET"

    @Composable
    override fun Primary(ctx: MomentRoomContext, modifier: Modifier) {
        val others = ctx.others
        // A heart that breathes, slowly enough to be restful rather than busy.
        val breath = rememberInfiniteTransition(label = "quietBreath")
        val glow by breath.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(4_000), RepeatMode.Reverse),
            label = "quietHeart",
        )
        Column(
            modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val names = when (others.size) {
                0 -> null
                1 -> firstName(others.first())
                2 -> "${firstName(others[0])} and ${firstName(others[1])}"
                else -> "${firstName(others[0])} and ${others.size - 1} others"
            }
            Text(
                names ?: "Just you, for now",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when (others.size) {
                    0 -> "The room will keep while you wait."
                    1 -> "Still here."
                    else -> "All still here."
                },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(36.dp))
            Text("♡", fontSize = 40.sp, color = Color.White, modifier = Modifier.alpha(glow))
            Spacer(Modifier.height(36.dp))
            if (others.isNotEmpty()) {
                Text(
                    if (others.size == 1) "You + ${firstName(others.first())}" else "You + ${others.size} people",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                togetherFor(ctx)?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
