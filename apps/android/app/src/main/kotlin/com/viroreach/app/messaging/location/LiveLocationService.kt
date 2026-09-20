package com.viroreach.app.messaging.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.viroreach.app.MainActivity
import com.viroreach.app.R
import com.viroreach.app.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps my own live locations moving while they run. A foreground service
 * because the sharing continues when Viro isn't on screen — and because the
 * person sharing should always see that it is happening, in one notification
 * they can stop it from.
 *
 * It stops itself as soon as no share of mine is still running.
 */
class LiveLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            scope.launch {
                val session = SessionManager.get(applicationContext)
                runCatching { session.messaging.myLiveLocations().forEach { session.messaging.stopLiveLocation(it.id) } }
                stopNow()
            }
            return START_NOT_STICKY
        }
        startForegroundSafely(1)
        if (ticker == null) ticker = scope.launch { run() }
        // Restarted after being killed: the shares are on the server, so the
        // loop below finds them again.
        return START_STICKY
    }

    private suspend fun run() {
        val session = SessionManager.get(applicationContext)
        while (scope.isActive) {
            val live = runCatching { session.messaging.myLiveLocations() }.getOrDefault(emptyList())
            if (live.isEmpty()) {
                stopNow()
                return
            }
            val fix = ViroLocation.current(applicationContext)
            if (fix != null) {
                for (m in live) {
                    session.messaging.updateLiveLocation(
                        messageId = m.id,
                        lat = fix.latitude,
                        lng = fix.longitude,
                        accuracy = fix.accuracy.takeIf { it > 0f }?.toDouble(),
                    )
                }
            } else {
                Log.i(TAG, "LIVE_LOCATION_NO_FIX")
            }
            startForegroundSafely(live.size)
            delay(UPDATE_EVERY_MS)
        }
    }

    private fun stopNow() {
        ticker?.cancel()
        ticker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundSafely(shares: Int) {
        val notification = notification(shares)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "LIVE_LOCATION_FOREGROUND_FAILED ${it.message}") }
    }

    private fun notification(shares: Int): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_viro)
            .setContentTitle("Sharing your live location")
            .setContentText(if (shares > 1) "In $shares chats" else "In one chat")
            .setContentIntent(open)
            .addAction(0, "Stop sharing", stop)
            .setOngoing(true)
            .setColor(0xFF1565F5.toInt())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Live location", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you are sharing your live location"
            },
        )
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ViroLiveLocation"
        private const val CHANNEL = "viro_live_location"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP_ALL = "com.viroreach.app.STOP_LIVE_LOCATION"

        /** Often enough to be useful on foot, rarely enough to spare the battery. */
        private const val UPDATE_EVERY_MS = 25_000L

        /** Called when a live share starts, and at app start if any is still running. */
        fun start(context: Context) {
            val intent = Intent(context, LiveLocationService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure { Log.w(TAG, "LIVE_LOCATION_START_FAILED ${it.message}") }
        }
    }
}

private fun CoroutineScope.cancel() {
    coroutineContext[Job]?.cancel()
}
