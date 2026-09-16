package com.viroreach.app.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.viroreach.app.MainActivity
import com.viroreach.app.R
import com.viroreach.feature.calling.IncomingCallInfo

class IncomingCallNotifier(private val context: Context) {
    private val notificationManager = NotificationManagerCompat.from(context)

    fun show(callerName: String, info: IncomingCallInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            REQUEST_FULL_SCREEN,
            MainActivity.incomingCallIntent(context, info.callId, callerName),
            pendingIntentFlags(),
        )
        val acceptIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_ACCEPT,
            IncomingCallActionReceiver.acceptIntent(context, info.callId),
            pendingIntentFlags(),
        )
        val declineIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_DECLINE,
            IncomingCallActionReceiver.declineIntent(context, info.callId),
            pendingIntentFlags(),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Incoming Viro Call")
            .setContentText(callerName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .addAction(0, "Decline", declineIntent)
            .addAction(0, "Accept", acceptIntent)
            .build()
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
    }

    fun dismiss() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Ringtone and alerts for incoming Viro Call sessions"
            setBypassDnd(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            ringtoneUri?.let { setSound(it, audioAttributes) }
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun pendingIntentFlags(): Int {
        val base = PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            base or PendingIntent.FLAG_IMMUTABLE
        } else {
            base
        }
    }

    companion object {
        const val CHANNEL_ID = "viro_incoming_calls"
        const val NOTIFICATION_ID = 4101
        private const val REQUEST_FULL_SCREEN = 1
        private const val REQUEST_ACCEPT = 2
        private const val REQUEST_DECLINE = 3
    }
}
