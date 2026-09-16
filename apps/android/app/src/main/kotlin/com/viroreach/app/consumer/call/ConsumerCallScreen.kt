package com.viroreach.app.consumer.call



import androidx.compose.foundation.layout.*

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.*

import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.dp

import com.viroreach.app.consumer.CallNavigationPolicy
import com.viroreach.app.consumer.resolveIncomingCallerPresentation

import com.viroreach.app.consumer.consumerCallStateLabel

import com.viroreach.app.consumer.consumerFailureMessage

import com.viroreach.app.consumer.consumerRouteLabel

import com.viroreach.app.session.SessionManager

import com.viroreach.core.designsystem.ViroColors

import com.viroreach.core.designsystem.ViroSpacing

import com.viroreach.core.designsystem.components.*

import com.viroreach.core.model.CallStateMachineState

import com.viroreach.feature.contacts.PhoneNumberFormatter

import kotlinx.coroutines.delay

import kotlinx.coroutines.launch



data class CallPresentation(

    val displayName: String,

    val avatarUrl: String? = null,

    val phoneE164: String? = null,

)



@Composable

fun ConsumerCallScreen(

    session: SessionManager,

    presentation: CallPresentation,

    onDismiss: () -> Unit,

    onOpenChat: () -> Unit,

    onAcceptIncoming: () -> Unit,

) {

    val callManager = session.callManager

    val state by callManager.state.collectAsState()

    val isCaller by callManager.isCallerRole.collectAsState()

    val route by callManager.routeLabel.collectAsState()

    var elapsed by remember { mutableIntStateOf(0) }

    var muted by remember { mutableStateOf(false) }

    var speaker by remember { mutableStateOf(false) }
    var showKeypad by remember { mutableStateOf(false) }
    var keypadDigits by remember { mutableStateOf("") }
    var resolvedPresentation by remember { mutableStateOf(presentation) }

    val scope = rememberCoroutineScope()
    val incomingCall by callManager.incomingCall.collectAsState()

    LaunchedEffect(incomingCall?.callId, presentation.displayName) {
        val info = incomingCall
        resolvedPresentation = if (info != null && !isCaller) {
            resolveIncomingCallerPresentation(session, info)
        } else {
            presentation
        }
    }

    val isIncomingRinging = CallNavigationPolicy.isIncomingRinging(state, isCaller)
    val isFailure = CallNavigationPolicy.isFailureState(state)
    val secondaryLine = resolvedPresentation.phoneE164?.let {
        PhoneNumberFormatter.formatE164International(it)
    }



    LaunchedEffect(state) {

        when (state) {

            CallStateMachineState.ACTIVE -> {

                elapsed = 0

                while (true) {

                    delay(1000)

                    elapsed++

                }

            }

            CallStateMachineState.ENDED, CallStateMachineState.IDLE -> {

                delay(1500)

                onDismiss()

            }

            else -> {

                if (CallNavigationPolicy.isFailureState(state)) {

                    delay(3000)

                    onDismiss()

                }

            }

        }

    }



    ViroScreenBackground {

        ViroSafeScreen(applyNavigationBarsPadding = true) {

            Column(

                modifier = Modifier

                    .fillMaxSize()

                    .padding(ViroSpacing.md),

                horizontalAlignment = Alignment.CenterHorizontally,

            ) {

                Spacer(Modifier.height(ViroSpacing.sm))

                if (isIncomingRinging) {

                    Text(

                        "Incoming Viro Call",

                        style = MaterialTheme.typography.labelLarge,

                        color = ViroColors.accent,

                    )

                } else {

                    Row(verticalAlignment = Alignment.CenterVertically) {

                        Icon(

                            Icons.Default.Lock,

                            contentDescription = null,

                            tint = ViroColors.success,

                            modifier = Modifier.size(16.dp),

                        )

                        Spacer(Modifier.width(6.dp))

                        Text(

                            "Encrypted voice call",

                            color = ViroColors.success,

                            style = MaterialTheme.typography.labelMedium,

                        )

                    }

                }

                Spacer(Modifier.weight(0.15f))

                ViroAvatar(

                    imageUrl = resolvedPresentation.avatarUrl,

                    displayName = resolvedPresentation.displayName,

                    size = ViroAvatarSize.Hero,

                )

                Spacer(Modifier.height(ViroSpacing.md))

                Text(

                    resolvedPresentation.displayName,

                    style = MaterialTheme.typography.headlineMedium,

                    color = ViroColors.textPrimary,

                    maxLines = 2,

                    overflow = TextOverflow.Ellipsis,

                )

                secondaryLine?.let {

                    Text(

                        it,

                        style = MaterialTheme.typography.bodyLarge,

                        color = ViroColors.textSecondary,

                        maxLines = 1,

                        overflow = TextOverflow.Ellipsis,

                    )

                }

                Spacer(Modifier.height(ViroSpacing.sm))

                Text(

                    text = if (isFailure) consumerFailureMessage(state) else consumerCallStateLabel(state, isCaller),

                    style = MaterialTheme.typography.titleMedium,

                    color = if (isFailure) ViroColors.consumerError else ViroColors.textSecondary,

                )

                if (state == CallStateMachineState.ACTIVE) {

                    Text(

                        formatElapsed(elapsed),

                        style = MaterialTheme.typography.displaySmall,

                        color = ViroColors.textPrimary,

                    )

                }

                consumerRouteLabel(route)?.let {

                    Text(it, style = MaterialTheme.typography.labelSmall, color = ViroColors.textMuted)

                }

                Spacer(Modifier.weight(1f))



                if (isIncomingRinging) {

                    ViroIncomingCallActions(

                        onDecline = {
                            session.incomingCallRinger.stop()
                            scope.launch { callManager.rejectCall() }
                        },

                        onAccept = onAcceptIncoming,

                    )

                } else {

                    if (state == CallStateMachineState.ACTIVE || state == CallStateMachineState.CONNECTING) {

                        ViroCallControlGrid(

                            controls = listOf(

                                ViroCallControl(

                                    id = "mute",

                                    label = if (muted) "Unmute" else "Mute",

                                    icon = Icons.Default.Mic,

                                    activeIcon = Icons.Default.MicOff,

                                    isActive = muted,

                                ),

                                ViroCallControl(

                                    id = "speaker",

                                    label = "Speaker",

                                    icon = Icons.Default.VolumeUp,

                                    activeIcon = Icons.Default.VolumeOff,

                                    isActive = speaker,

                                ),

                                ViroCallControl(

                                    id = "keypad",

                                    label = "Keypad",

                                    icon = Icons.Default.Dialpad,

                                ),

                                ViroCallControl(

                                    id = "chat",

                                    label = "Message",

                                    icon = Icons.Default.Chat,

                                ),

                            ),

                            onControl = { id ->

                                when (id) {

                                    "mute" -> {

                                        muted = !muted

                                        callManager.setMuted(muted)

                                    }

                                    "speaker" -> {

                                        speaker = !speaker

                                        callManager.setSpeaker(speaker)

                                    }

                                    "chat" -> onOpenChat()
                                    "keypad" -> showKeypad = true
                                    else -> Unit

                                }

                            },

                        )

                        Spacer(Modifier.height(ViroSpacing.lg))

                    }

                    ViroEndCallButton(

                        onClick = {

                            scope.launch {

                                if (isIncomingRinging) callManager.rejectCall() else callManager.hangUp()

                                onDismiss()

                            }

                        },

                    )

                }

                Spacer(Modifier.height(ViroSpacing.lg))

            }

            if (showKeypad) {
                InCallKeypadOverlay(
                    digits = keypadDigits,
                    onDigit = { d ->
                        if (keypadDigits.length < 20) keypadDigits += d
                    },
                    onBackspace = {
                        if (keypadDigits.isNotEmpty()) keypadDigits = keypadDigits.dropLast(1)
                    },
                    onDismiss = { showKeypad = false },
                )
            }
        }

    }

}

@Composable
private fun InCallKeypadOverlay(
    digits: String,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(ViroSpacing.md),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = ViroColors.NavySurfaceElevated.copy(alpha = 0.96f),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(ViroSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Keypad", style = MaterialTheme.typography.titleMedium, color = ViroColors.textPrimary)
                    TextButton(onClick = onDismiss) {
                        Text("Done", color = ViroColors.accent)
                    }
                }
                Text(
                    text = digits.ifBlank { "Enter digits" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = ViroColors.textPrimary,
                    modifier = Modifier.padding(vertical = ViroSpacing.sm),
                )
                ViroNumericKeypad(
                    onDigit = onDigit,
                    onBackspace = onBackspace,
                    modifier = Modifier.padding(bottom = ViroSpacing.sm),
                )
            }
        }
    }
}



private fun formatElapsed(seconds: Int): String {

    val m = seconds / 60

    val s = seconds % 60

    return "%d:%02d".format(m, s)

}

