package com.viroreach.app.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.viroreach.app.MainActivity
import com.viroreach.app.relationships.ReminderNotifications
import com.viroreach.app.relationships.ReminderScheduler
import com.viroreach.app.session.SessionManager
import com.viroreach.feature.calling.IncomingCallInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wakes the app for an incoming call when the signaling socket isn't there to
 * deliver one — a backgrounded or killed app, a dropped WebSocket, a dozing
 * device. Without this the call simply never rings: the server sends the push
 * (CallsService.authorize and inviteToCall both do), but nothing on the phone
 * was listening for it.
 *
 * The payload is a DATA message, not a notification message, so this callback
 * runs even when the app is in the background. A notification-type message
 * would be handed straight to the system tray and never reach code, which is
 * useless for a call that has to raise a full-screen ringer.
 */
class ViroFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM_TOKEN_REFRESHED")
        // A token can rotate at any time (reinstall, restore, cache clear). If
        // the server keeps the stale one, the next call to this device is
        // delivered to nothing, so re-register as soon as we hear about it.
        scope.launch {
            runCatching { SessionManager.get(applicationContext).registerPushToken(token) }
                .onFailure { Log.w(TAG, "FCM_TOKEN_REGISTER_FAILED ${it.message}") }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] == "moment-invite") {
            val session = SessionManager.get(applicationContext)
            scope.launch {
                session.moments.refreshInvitations()
                runCatching { session.callManager.ensureSignalingReady() }
            }
            return
        }
        if (data["type"] == "message" || data["type"] == "loop") {
            // The socket was not there to deliver it; fetch now, so the chat
            // is already up to date when the notification is opened.
            val session = SessionManager.get(applicationContext)
            session.messaging.syncSoon()
            scope.launch { runCatching { session.callManager.ensureSignalingReady() } }
            return
        }
        if (data["type"] == "connection_request" || data["type"] == "connection_accepted") {
            val session = SessionManager.get(applicationContext)
            scope.launch { runCatching { session.people.refreshConnections() } }
            notifyConnection(
                accepted = data["type"] == "connection_accepted",
                title = message.notification?.title ?: if (data["type"] == "connection_accepted") "Connection accepted" else "Connection request",
                body = message.notification?.body.orEmpty(),
                key = data["connectionId"].orEmpty(),
            )
            return
        }
        if (data["type"] != TYPE_INCOMING_CALL) {
            Log.i(TAG, "PUSH_IGNORED type=${data["type"]}")
            return
        }
        val callId = data["callId"].orEmpty()
        if (callId.isBlank()) {
            Log.w(TAG, "PUSH_INCOMING_CALL_NO_CALLID")
            return
        }
        val session = SessionManager.get(applicationContext)
        val info = IncomingCallInfo(
            callId = callId,
            callerUserId = data["callerUserId"],
            callerDeviceId = data["callerDeviceId"],
            callerPhoneE164 = data["callerPhoneE164"],
            callerDisplayName = data["callerDisplayName"],
        )
        val callerName = info.callerDisplayName
            ?: info.callerPhoneE164
            ?: "Viro call"
        Log.i(TAG, "PUSH_INCOMING_CALL callId=$callId")
        session.incomingCallNotifier.show(callerName, info)

        // The ringer is up, but answering needs the signaling socket: accept,
        // reject and hangup all travel over it, and the callee has to be
        // reachable there before the caller can be told the call was taken.
        // Reconnecting here means the socket is usually live by the time the
        // user reaches for the phone.
        scope.launch {
            runCatching { session.callManager.ensureSignalingReady() }
                .onFailure { Log.w(TAG, "PUSH_SIGNALING_WAKE_FAILED ${it.message}") }
        }
    }

    /** A connection request, or someone accepting mine: opens Add people. */
    private fun notifyConnection(accepted: Boolean, title: String, body: String, key: String) {
        val intent = ReminderNotifications.openIntent(
            applicationContext,
            MainActivity.OPEN_CONNECTIONS_REQUESTS,
            requestCode = ("conn:" + key).hashCode(),
        )
        ReminderNotifications.post(
            context = applicationContext,
            id = ("conn:" + key).hashCode(),
            channel = if (accepted) ReminderScheduler.CH_LOW else ReminderScheduler.CH_NORMAL,
            title = title,
            body = body,
            intent = intent,
        )
    }

    companion object {
        private const val TAG = "ViroPush"
        private const val TYPE_INCOMING_CALL = "incoming_call"
    }
}
