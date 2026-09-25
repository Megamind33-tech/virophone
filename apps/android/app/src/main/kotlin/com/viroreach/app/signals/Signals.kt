package com.viroreach.app.signals

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import coil.imageLoader
import coil.request.ImageRequest
import com.viroreach.app.MainActivity
import com.viroreach.app.relationships.ReminderNotifications
import com.viroreach.app.session.SessionManager
import com.viroreach.core.network.AckSignalBody
import com.viroreach.core.network.RespondSignalBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Intimate signals: one person reaching for another without a call, a text or
 * a reaction. A signal is a presence event — it arrives over push, is felt
 * wherever the recipient is, and is never chat content. This object is the
 * delivery coordinator: it decides where a signal is presented (in Viro or on
 * the system), keeps repeats from becoming interruptions, and carries the
 * recipient's own choices about sound and touch.
 */
object SignalPresenter {

    /** The eight signals, in the order they are offered. */
    val KINDS = listOf(
        "THINKING_OF_YOU", "MISS_YOU", "HERE", "NEED_YOUR_VOICE",
        "PROUD", "MADE_ME_SMILE", "HOLD_ME", "CHECKING_ON_YOU",
    )

    /** What the recipient reads, past tense: "Mosty thought of you". */
    fun phrase(kind: String): String = when (kind) {
        "THINKING_OF_YOU" -> "thought of you"
        "MISS_YOU" -> "is missing you"
        "HERE" -> "is here"
        "NEED_YOUR_VOICE" -> "needs your voice"
        "PROUD" -> "is proud of you"
        "MADE_ME_SMILE" -> "smiled because of you"
        "HOLD_ME" -> "wants to hold you"
        "CHECKING_ON_YOU" -> "is checking on you"
        else -> "reached for you"
    }

    /** The one small answer this kind invites. */
    fun answerKind(kind: String): String = when (kind) {
        "NEED_YOUR_VOICE" -> "HERE"
        "HOLD_ME" -> "HOLD_ME"
        "CHECKING_ON_YOU" -> "HERE"
        else -> "THINKING_OF_YOU"
    }

    /** What both people read when they reached for each other. */
    fun mutualPhrase(kind: String): String = when (kind) {
        "MISS_YOU" -> "Missing each other"
        "HERE" -> "Together"
        else -> "On each other's minds"
    }

    /** Recipient's own choice. A sender can never force sound. */
    const val MODE_SILENT = 0
    const val MODE_GENTLE = 1
    const val MODE_SOUND = 2

    fun mode(context: Context): Int =
        context.getSharedPreferences("viro_signals", Context.MODE_PRIVATE).getInt("mode", MODE_GENTLE)

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences("viro_signals", Context.MODE_PRIVATE).edit().putInt("mode", mode).apply()
    }

    fun modeLabel(mode: Int): String = when (mode) {
        MODE_SILENT -> "Silent"
        MODE_GENTLE -> "Gentle"
        else -> "Sound and touch"
    }

    /** Set from the activity lifecycle: where a signal may be shown in Viro. */
    @Volatile
    var foreground: Boolean = false

    private val _incoming = MutableStateFlow<IncomingSignal?>(null)
    /** The signal currently being presented inside Viro, if any. */
    val incoming: StateFlow<IncomingSignal?> = _incoming

    private val queue = ArrayDeque<IncomingSignal>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onSignal(context: Context, data: Map<String, String>) {
        val signal = IncomingSignal(
            signalId = data["signalId"].orEmpty(),
            kind = data["kind"] ?: "THINKING_OF_YOU",
            fromUserId = data["fromUserId"].orEmpty(),
            fromName = data["fromName"] ?: "Someone",
            fromAvatar = data["fromAvatar"].orEmpty().ifBlank { null },
            count = data["count"]?.toIntOrNull() ?: 1,
            todayCount = data["todayCount"]?.toIntOrNull() ?: 1,
            mutual = data["mutual"] == "true",
        )
        if (signal.signalId.isBlank() || signal.fromUserId.isBlank()) return
        // A burst reaches the phone as updates of one aggregated signal. The
        // same id replaces whatever it already said — never a second show.
        val current = _incoming.value
        if (current != null && current.signalId == signal.signalId) {
            _incoming.value = signal
        } else if (current == null) {
            _incoming.value = signal
        } else {
            queue.addLast(signal)
        }
        if (foreground) {
            feel(context, signal.kind, signal.mutual)
            ack(context, signal.signalId, "PRESENTED")
        } else {
            SignalNotifier.show(context, signal)
            ack(context, signal.signalId, "RECEIVED")
        }
    }

    /** A mutual reach or an answer arrives as its own small event. */
    fun onResponseSignal(context: Context, data: Map<String, String>) {
        onSignal(
            context,
            buildMap {
                putAll(data)
                put("signalId", data["signalId"] ?: "resp-${data["fromUserId"]}-${System.currentTimeMillis()}")
                put("count", "1")
                put("todayCount", "1")
                put("mutual", data["mutual"] ?: "false")
            },
        )
    }

    fun dismissCurrent(context: Context) {
        _incoming.value?.let { ack(context, it.signalId, "OPENED") }
        _incoming.value = queue.removeFirstOrNull()
        if (_incoming.value != null) feel(context, _incoming.value!!.kind, _incoming.value!!.mutual)
    }

    fun respond(context: Context, signalId: String, kind: String) {
        val session = SessionManager.get(context)
        scope.launch {
            runCatching { session.api.respondSignal(signalId, RespondSignalBody(kind)) }
        }
    }

    private fun ack(context: Context, signalId: String, state: String) {
        val session = SessionManager.get(context)
        scope.launch {
            runCatching { session.api.ackSignal(signalId, AckSignalBody(state)) }
        }
    }

    /**
     * The touch of a signal. Each kind has its own waveform — a heartbeat for
     * Thinking of you, a slow reassure for I'm here — and none of them rings.
     * Sound only ever if the recipient chose it and the phone allows it.
     */
    fun feel(context: Context, kind: String, mutual: Boolean = false) {
        val m = mode(context)
        if (m == MODE_SILENT) return
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = when (kind) {
            "THINKING_OF_YOU" -> longArrayOf(0, 90, 120, 150)          // a heartbeat
            "MISS_YOU" -> longArrayOf(0, 140, 110, 200)                // a deeper double
            "HERE" -> longArrayOf(0, 300, 200, 300)                    // slow reassure
            "NEED_YOUR_VOICE" -> longArrayOf(0, 120, 80, 120, 80, 220)
            "PROUD" -> longArrayOf(0, 60, 90, 60, 90, 60)              // light lift
            "MADE_ME_SMILE" -> longArrayOf(0, 70, 70, 70)
            "HOLD_ME" -> longArrayOf(0, 500)                           // one long hold
            "CHECKING_ON_YOU" -> longArrayOf(0, 100, 100, 100)
            else -> longArrayOf(0, 90, 120, 150)
        }
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(if (mutual) pattern + pattern else pattern, -1)) }
        if (m == MODE_SOUND) {
            runCatching {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55).apply {
                        startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
                    }
                }
            }
        }
    }
}

/** Everything a phone needs to present a signal without asking the server. */
data class IncomingSignal(
    val signalId: String,
    val kind: String,
    val fromUserId: String,
    val fromName: String,
    val fromAvatar: String?,
    val count: Int,
    val todayCount: Int,
    val mutual: Boolean,
) {
    /** "Mosty thought of you", or — after repeats — "Mosty is really thinking of you". */
    fun headline(): String {
        val first = fromName.substringBefore(' ').ifBlank { fromName }
        return if (count >= 5) {
            when (kind) {
                "THINKING_OF_YOU" -> "$first is really thinking of you"
                "MISS_YOU" -> "$first really misses you"
                else -> "$first ${SignalPresenter.phrase(kind)}"
            }
        } else {
            "$first ${SignalPresenter.phrase(kind)}"
        }
    }

    /** "5 times today" — private, for the two of them, never a public counter. */
    fun subline(): String = when {
        mutual -> SignalPresenter.mutualPhrase(kind)
        todayCount > 1 -> "$todayCount times today"
        else -> ""
    }
}

/**
 * The signal as the system sees it: a high-priority heads-up on its own
 * channel, with the sender's face, that retreats by itself. This is the
 * OS-permitted presentation for a Viro that is not on screen — Android offers
 * no sanctioned way to animate over another developer's app, and overlay
 * permissions are not asked for. The full animated surface lives inside Viro.
 */
object SignalNotifier {
    private const val CHANNEL = "viro_signals"

    fun show(context: Context, signal: IncomingSignal) {
        val nm = NotificationManagerCompat.from(context)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val channel = android.app.NotificationChannel(
                CHANNEL,
                "Signals from your people",
                android.app.NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "When someone reaches for you without a call or a message."
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 90, 120, 150)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
        if (!ReminderNotifications.canPost(context)) return

        val face = avatar(context, signal)
        val open = ReminderNotifications.openIntent(
            context, MainActivity.OPEN_CHAT, subjectUserId = signal.fromUserId,
            requestCode = ("signal:" + signal.fromUserId).hashCode(),
        )
        val answer = SignalPresenter.answerKind(signal.kind)
        val respondIntent = PendingIntent.getBroadcast(
            context,
            ("signal-resp:" + signal.signalId).hashCode(),
            Intent(context, SignalActionReceiver::class.java).apply {
                putExtra(SignalActionReceiver.EXTRA_SIGNAL_ID, signal.signalId)
                putExtra(SignalActionReceiver.EXTRA_KIND, answer)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = signal.subline().ifBlank { "Right now" }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.viroreach.app.R.drawable.ic_stat_viro)
            .setColor(0xFF1565F5.toInt())
            .setLargeIcon(face)
            .setContentTitle(signal.headline())
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(8000)
            .addAction(0, when (answer) {
                "HERE" -> "I'm here"
                "HOLD_ME" -> "Holding you"
                else -> "Thinking of you too"
            }, respondIntent)
            .build()
        runCatching { nm.notify(("signal:" + signal.signalId).hashCode(), n) }
    }

    /** The sender's real face when there is one; quietly nothing when not. */
    private fun avatar(context: Context, signal: IncomingSignal): android.graphics.Bitmap? {
        val url = signal.fromAvatar ?: return null
        return runCatching {
            val request = ImageRequest.Builder(context).data(url).size(192).allowHardware(false).build()
            val drawable = kotlinx.coroutines.runBlocking {
                withTimeoutOrNull(2500) { context.imageLoader.execute(request).drawable }
            }
            (drawable as? BitmapDrawable)?.bitmap
        }.getOrNull()
    }
}

/** The one action a notification carries: answer the signal with a signal. */
class SignalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_SIGNAL_ID) ?: return
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "THINKING_OF_YOU"
        NotificationManagerCompat.from(context).cancel(("signal:" + id).hashCode())
        SignalPresenter.respond(context, id, kind)
        // The answer is itself a touch: the sender's phone shows it, and this
        // one quietly confirms it was sent.
        ContextCompat.getMainExecutor(context).execute {
            SignalPresenter.feel(context, kind)
        }
    }

    companion object {
        const val EXTRA_SIGNAL_ID = "signalId"
        const val EXTRA_KIND = "kind"
    }
}
