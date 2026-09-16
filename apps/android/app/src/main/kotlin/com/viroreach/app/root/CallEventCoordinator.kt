package com.viroreach.app.root



import android.Manifest

import android.content.pm.PackageManager

import android.os.Build

import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.runtime.Composable

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.getValue

import androidx.compose.ui.platform.LocalContext

import androidx.core.content.ContextCompat

import androidx.compose.runtime.collectAsState

import com.viroreach.app.consumer.resolveIncomingCallerPresentation

import com.viroreach.app.session.SessionManager

import com.viroreach.core.model.CallStateMachineState




/**

 * Root-level call observer: incoming-call notifications when the app is backgrounded.

 * Full-screen in-app UI remains in [com.viroreach.app.consumer.ConsumerNav].

 */

@Composable

fun CallEventCoordinator(

    session: SessionManager,

    content: @Composable () -> Unit,

) {

    val context = LocalContext.current

    val incomingCall by session.callManager.incomingCall.collectAsState()

    val callState by session.callManager.state.collectAsState()

    val isCaller by session.callManager.isCallerRole.collectAsState()



    val notificationPermissionLauncher = rememberLauncherForActivityResult(

        ActivityResultContracts.RequestPermission(),

    ) { /* granted or denied — notification still attempted when allowed */ }



    LaunchedEffect(Unit) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&

            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)

            != PackageManager.PERMISSION_GRANTED

        ) {

            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        }

    }



    LaunchedEffect(incomingCall, callState, isCaller) {

        val info = incomingCall

        if (info != null && callState == CallStateMachineState.RINGING && !isCaller) {

            val presentation = runCatching {
                resolveIncomingCallerPresentation(session, info)
            }.getOrElse {
                com.viroreach.app.consumer.call.CallPresentation(
                    displayName = info.callerDisplayName?.takeIf { it.isNotBlank() } ?: "Incoming call",
                    phoneE164 = info.callerPhoneE164,
                )
            }

            runCatching {
                session.incomingCallNotifier.show(presentation.displayName, info)
            }

            runCatching { session.incomingCallRinger.start() }

        } else {

            session.incomingCallNotifier.dismiss()

            session.incomingCallRinger.stop()

        }

    }



    LaunchedEffect(callState) {

        if (callState in TERMINAL_CALL_STATES) {

            session.incomingCallRinger.stop()

            session.incomingCallNotifier.dismiss()

        }

    }



    content()

}



private val TERMINAL_CALL_STATES = setOf(

    CallStateMachineState.ENDED,

    CallStateMachineState.IDLE,

    CallStateMachineState.FAILED,

    CallStateMachineState.MEDIA_FAILED,

    CallStateMachineState.NETWORK_FAILED,

    CallStateMachineState.SERVER_FAILED,

    CallStateMachineState.PEER_REJECTED,

    CallStateMachineState.TIMEOUT,

)

