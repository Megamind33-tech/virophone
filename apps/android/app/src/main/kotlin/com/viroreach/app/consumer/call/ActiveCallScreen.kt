package com.viroreach.app.consumer.call



import androidx.compose.foundation.layout.*

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.*

import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.dp

import com.viroreach.app.consumer.consumerCallStateLabel

import com.viroreach.app.consumer.consumerRouteLabel

import com.viroreach.app.session.SessionManager

import com.viroreach.core.designsystem.ViroColors

import com.viroreach.core.designsystem.ViroSpacing

import com.viroreach.core.designsystem.components.*

import com.viroreach.core.model.CallStateMachineState

import kotlinx.coroutines.delay

import kotlinx.coroutines.launch



@Composable

fun ActiveCallScreen(

    session: SessionManager,

    displayName: String,

    avatarUrl: String? = null,

    onEndCall: () -> Unit,

    onOpenChat: () -> Unit,

) {

    val callManager = session.callManager

    val state by callManager.state.collectAsState()

    val route by callManager.routeLabel.collectAsState()

    var elapsed by remember { mutableIntStateOf(0) }

    var muted by remember { mutableStateOf(false) }

    var speaker by remember { mutableStateOf(false) }



    LaunchedEffect(state) {

        if (state == CallStateMachineState.ACTIVE) {

            while (true) {

                delay(1000)

                elapsed++

            }

        }

    }



    val scope = rememberCoroutineScope()

    val routeLabel = consumerRouteLabel(route)



    ViroScreenBackground {

        ViroSafeScreen {

            Column(

                modifier = Modifier

                    .fillMaxSize()

                    .padding(ViroSpacing.md),

                horizontalAlignment = Alignment.CenterHorizontally,

            ) {

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {

                    ViroBackButton(onClick = onEndCall)

                    Icon(Icons.Default.Lock, contentDescription = null, tint = ViroColors.GreenAvailable, modifier = Modifier.size(20.dp))

                }

                Spacer(Modifier.height(8.dp))

                Text(

                    "Encrypted voice call",

                    color = ViroColors.GreenAvailable,

                    style = MaterialTheme.typography.labelMedium,

                )

                Spacer(Modifier.height(ViroSpacing.lg))

                ViroAvatar(

                    displayName = displayName,

                    imageUrl = avatarUrl,

                    size = ViroAvatarSize.Hero,

                )

                Spacer(Modifier.height(ViroSpacing.md))

                Text(

                    displayName,

                    style = MaterialTheme.typography.headlineMedium,

                    color = Color.White,

                    maxLines = 1,

                    overflow = TextOverflow.Ellipsis,

                )

                Text(

                    consumerCallStateLabel(state),

                    style = MaterialTheme.typography.titleMedium,

                    color = MaterialTheme.colorScheme.onSurfaceVariant,

                )

                if (state == CallStateMachineState.ACTIVE) {

                    Text(

                        formatElapsed(elapsed),

                        style = MaterialTheme.typography.displaySmall,

                        color = Color.White,

                    )

                }

                routeLabel?.let {

                    Text(it, style = MaterialTheme.typography.labelSmall, color = ViroColors.MutedBlue)

                }

                Spacer(Modifier.weight(1f))

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

                            else -> Unit

                        }

                    },

                )

                Spacer(Modifier.height(ViroSpacing.lg))

                ViroEndCallButton(

                    onClick = {

                        scope.launch {

                            callManager.hangUp()

                            onEndCall()

                        }

                    },

                )

                Spacer(Modifier.height(ViroSpacing.md))

            }

        }

    }

}



private fun formatElapsed(seconds: Int): String {

    val m = seconds / 60

    val s = seconds % 60

    return "%d:%02d".format(m, s)

}

