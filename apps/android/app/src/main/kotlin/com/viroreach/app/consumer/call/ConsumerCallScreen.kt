package com.viroreach.app.consumer.call



import androidx.compose.foundation.clickable
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
    val remotePeerUnstable by callManager.remotePeerUnstable.collectAsState()
    val muted by callManager.isMuted.collectAsState()
    val onHold by callManager.isOnHold.collectAsState()
    val peerOnHold by callManager.peerOnHold.collectAsState()

    var elapsed by remember { mutableIntStateOf(0) }
    // LaunchedEffect(state) re-runs its whole body on every state change,
    // including a RECONNECTING dip back to ACTIVE mid-call — without this
    // gate, elapsed got reset to 0 on every single reconnect instead of only
    // when the call first became active.
    var hasBeenActive by remember { mutableStateOf(false) }

    var speaker by remember { mutableStateOf(false) }
    var showKeypad by remember { mutableStateOf(false) }

    var showAddCaller by remember { mutableStateOf(false) }
    var addCallerBusy by remember { mutableStateOf(false) }
    val participants by callManager.callParticipants.collectAsState()
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

                if (!hasBeenActive) {
                    elapsed = 0
                    hasBeenActive = true
                }

                while (true) {

                    delay(1000)

                    elapsed++

                }

            }

            CallStateMachineState.ENDED, CallStateMachineState.IDLE -> {

                hasBeenActive = false

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

                // callState's own RECONNECTING only ever reflects this
                // device's own connection — without this, the other party's
                // audio just went silent with nothing on screen to explain
                // why, since their reconnect is invisible from here.
                if (state == CallStateMachineState.ACTIVE && remotePeerUnstable) {

                    Text(

                        "Their connection is unstable…",

                        style = MaterialTheme.typography.labelSmall,

                        color = ViroColors.consumerError,

                    )

                }

                if (state == CallStateMachineState.ACTIVE && peerOnHold) {

                    Text(

                        "${resolvedPresentation.displayName} put you on hold",

                        style = MaterialTheme.typography.labelSmall,

                        color = ViroColors.textMuted,

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

                                    id = "add",

                                    label = "Add caller",

                                    icon = Icons.Default.PersonAdd,

                                ),

                                ViroCallControl(

                                    id = "hold",

                                    label = if (onHold) "Resume" else "Hold",

                                    icon = Icons.Default.Pause,

                                    activeIcon = Icons.Default.PlayArrow,

                                    isActive = onHold,

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

                                    "mute" -> callManager.setMuted(!muted)

                                    "speaker" -> {

                                        speaker = !speaker

                                        callManager.setSpeaker(speaker)

                                    }

                                    "add" -> showAddCaller = true

                                    "hold" -> {

                                        if (onHold) callManager.resumeCall() else callManager.holdCall()

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


            if (showAddCaller) {
                AddCallerSheet(
                    contacts = session.contactsCoordinator.uiState.contacts,
                    alreadyOnCall = participants.map { it.identity }.toSet(),
                    busy = addCallerBusy,
                    onPick = { contact ->
                        val userId = contact.userId ?: return@AddCallerSheet
                        addCallerBusy = true
                        scope.launch {
                            callManager.addParticipant(userId)
                            addCallerBusy = false
                            showAddCaller = false
                        }
                    },
                    onDismiss = { if (!addCallerBusy) showAddCaller = false },
                )
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


/**
 * Contact picker for adding someone to a call already in progress.
 *
 * Only contacts that are reachable on Viro can be listed: adding a person
 * means ringing them into this call's existing LiveKit room, which a plain
 * phone number cannot join. Anyone already in the room is shown as such
 * rather than hidden, so the list doesn't silently lose people.
 */
@Composable
private fun AddCallerSheet(
    contacts: List<com.viroreach.app.consumer.ContactListItem>,
    alreadyOnCall: Set<String>,
    busy: Boolean,
    onPick: (com.viroreach.app.consumer.ContactListItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = remember(contacts) {
        contacts
            .filter { it.userId != null && it.isReachable && !it.isBlocked }
            .sortedBy { it.effectiveDisplayName.lowercase() }
    }
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
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Add to call",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(ViroSpacing.sm))
                }
                if (candidates.isEmpty()) {
                    Text(
                        "No contacts on Viro to add yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ViroColors.textMuted,
                        modifier = Modifier.padding(vertical = ViroSpacing.md),
                    )
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        items(candidates.size) { index ->
                            val contact = candidates[index]
                            val onCall = contact.userId in alreadyOnCall
                            ListItem(
                                headlineContent = { Text(contact.effectiveDisplayName) },
                                supportingContent = {
                                    Text(
                                        if (onCall) "Already on this call"
                                        else contact.phoneE164.orEmpty(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                modifier = Modifier.then(
                                    if (onCall || busy) Modifier
                                    else Modifier.clickable { onPick(contact) },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
