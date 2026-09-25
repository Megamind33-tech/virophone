package com.viroreach.app.signals

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.components.ViroAvatar
import com.viroreach.core.designsystem.components.ViroAvatarSize
import kotlinx.coroutines.delay

/**
 * The signal as Viro itself presents it: over whatever screen is open, upper
 * centre, brief. A face appears inside a soft ring that breathes, the words
 * arrive under it, and the whole thing withdraws — four seconds, no modal,
 * nothing to dismiss. Touching it goes to the person; the small line under
 * the words answers the signal with one.
 */
@Composable
fun ViroPresenceSurface(onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val signal by SignalPresenter.incoming.collectAsState()
    val current = signal ?: return
    val appear = remember(current.signalId) { Animatable(0f) }
    val ring = rememberInfiniteTransition(label = "signal-ring")
    val pulse by ring.animateFloat(
        0.94f, 1.10f,
        infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "signal-pulse",
    )
    LaunchedEffect(current.signalId) {
        appear.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        // It retreats by itself: nobody has to dismiss a feeling.
        delay(4200)
        SignalPresenter.dismissCurrent(context)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f * appear.value))
            .clickable { onOpenChat(current.fromUserId); SignalPresenter.dismissCurrent(context) },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = 96.dp)
                .graphicsLayer {
                    alpha = appear.value
                    scaleX = 0.86f + 0.14f * appear.value
                    scaleY = 0.86f + 0.14f * appear.value
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                // The ring breathes around the face; nothing else moves.
                Box(
                    Modifier
                        .size(ViroAvatarSize.Large.diameter + 20.dp)
                        .graphicsLayer { scaleX = pulse; scaleY = pulse }
                        .border(1.dp, ViroColors.accent.copy(alpha = 0.30f), CircleShape),
                )
                ViroAvatar(
                    size = ViroAvatarSize.Large,
                    imageUrl = current.fromAvatar,
                    displayName = current.fromName,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                current.headline(),
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            current.subline().takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable {
                        SignalPresenter.respond(context, current.signalId, SignalPresenter.answerKind(current.kind))
                        SignalPresenter.dismissCurrent(context)
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (SignalPresenter.answerKind(current.kind)) {
                        "HERE" -> "I'm here"
                        "HOLD_ME" -> "Holding you"
                        else -> "Thinking of you too"
                    },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** The chat's way in: pick one of the eight, or check how loudly they land. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalPickerSheet(
    peerName: String,
    onSend: (kind: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(SignalPresenter.mode(context)) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ViroColors.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Reach for $peerName", color = ViroColors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "They feel it wherever they are — no call, no message.",
                color = ViroColors.textMuted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            SignalPresenter.KINDS.forEach { kind ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSend(kind); onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(when (kind) {
                        "THINKING_OF_YOU" -> "Thinking of you"
                        "MISS_YOU" -> "I miss you"
                        "HERE" -> "I'm here"
                        "NEED_YOUR_VOICE" -> "Need your voice"
                        "PROUD" -> "Proud of you"
                        "MADE_ME_SMILE" -> "You made me smile"
                        "HOLD_ME" -> "Hold me"
                        else -> "Just checking on you"
                    }, color = ViroColors.textPrimary, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = {
                val next = (mode + 1) % 3
                mode = next
                SignalPresenter.setMode(context, next)
            }) {
                Text(
                    "Incoming signals: ${SignalPresenter.modeLabel(mode)} — tap to change",
                    color = ViroColors.textMuted, fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
