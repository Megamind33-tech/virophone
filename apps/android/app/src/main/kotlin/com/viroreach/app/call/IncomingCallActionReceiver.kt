package com.viroreach.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.viroreach.app.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = SessionManager.peekInstance() ?: SessionManager.get(context)
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        val pendingResult = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_ACCEPT -> session.callManager.acceptCall(callId)
                    ACTION_DECLINE -> session.callManager.rejectCall()
                }
            } catch (_: Exception) {
                // Avoid process crash from notification actions.
            } finally {
                session.incomingCallRinger.stop()
                session.incomingCallNotifier.dismiss()
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.viroreach.app.action.ACCEPT_INCOMING_CALL"
        const val ACTION_DECLINE = "com.viroreach.app.action.DECLINE_INCOMING_CALL"
        const val EXTRA_CALL_ID = "call_id"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun acceptIntent(context: Context, callId: String): Intent =
            Intent(context, IncomingCallActionReceiver::class.java).apply {
                action = ACTION_ACCEPT
                putExtra(EXTRA_CALL_ID, callId)
            }

        fun declineIntent(context: Context, callId: String): Intent =
            Intent(context, IncomingCallActionReceiver::class.java).apply {
                action = ACTION_DECLINE
                putExtra(EXTRA_CALL_ID, callId)
            }
    }
}
