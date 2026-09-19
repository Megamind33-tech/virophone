package com.viroreach.app.relationships

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.viroreach.app.MainActivity
import com.viroreach.app.R
import com.viroreach.app.session.SessionManager
import com.viroreach.core.network.CommitmentDto
import com.viroreach.core.network.LoopDto
import com.viroreach.core.network.NudgeDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * When relationship reminders run. Everything is WorkManager so it survives
 * the app being closed or the phone restarting:
 *  - a light check every few hours (targets falling behind, dates, Loops);
 *  - the morning brief at the user's chosen time;
 *  - one exact-time job per open commitment ("You promised to call Mum at 19:00");
 *  - a job at the next time a Loop opens.
 */
object ReminderScheduler {
    private const val PERIODIC = "viro_relationship_nudges"
    private const val BRIEF = "viro_morning_brief"
    private const val LOOPS = "viro_loop_open"

    fun start(context: Context) {
        ensureChannels(context)
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NudgeWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
        scheduleBrief(context, "08:00", true, keepExisting = true)
        scheduleLoopReminders(context)
    }

    fun scheduleBrief(context: Context, hhmm: String, enabled: Boolean, keepExisting: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(BRIEF)
            return
        }
        val delay = delayUntil(parseTime(hhmm) ?: LocalTime.of(8, 0))
        wm.enqueueUniqueWork(
            BRIEF,
            if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BriefWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).build(),
        )
    }

    fun scheduleCommitments(context: Context, commitments: List<CommitmentDto>) {
        val wm = WorkManager.getInstance(context)
        for (c in commitments) {
            if (c.status != null && c.status != "OPEN") {
                cancelCommitment(context, c.id)
                continue
            }
            val due = runCatching { Instant.parse(c.dueAt) }.getOrNull() ?: continue
            val delay = Duration.between(Instant.now(), due).toMillis().coerceAtLeast(0)
            // Long overdue: the periodic check and the brief already cover it.
            if (delay == 0L && Duration.between(due, Instant.now()).toHours() > 12) continue
            wm.enqueueUniqueWork(
                "commitment:${c.id}",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CommitmentWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("id" to c.id))
                    .build(),
            )
        }
    }

    fun cancelCommitment(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("commitment:$id")
        NotificationManagerCompat.from(context).cancel(id.hashCode())
    }

    fun scheduleLoopReminders(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val loops = runCatching { SessionManager.get(context).relationships.myLoops() }.getOrDefault(emptyList())
            val next = loops.filter { it.active == true }.mapNotNull { nextOpening(it) }.minOrNull() ?: return@launch
            WorkManager.getInstance(context).enqueueUniqueWork(
                LOOPS,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<LoopOpenWorker>()
                    .setInitialDelay(Duration.between(LocalDateTime.now(), next).toMillis().coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
                    .build(),
            )
        }
    }

    /** The next local moment this Loop opens. */
    fun nextOpening(loop: LoopDto, from: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val time = parseTime(loop.timeOfDay ?: "19:00") ?: return null
        for (d in 0..31) {
            val date = from.toLocalDate().plusDays(d.toLong())
            val at = LocalDateTime.of(date, time)
            if (!at.isAfter(from)) continue
            val dow = date.dayOfWeek
            val open = when (loop.frequency) {
                "DAILY" -> true
                "WEEKDAYS" -> dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
                "CUSTOM" -> (loop.daysMask ?: 0) and (1 shl (dow.value % 7)) != 0
                "WEEKLY" -> dow == DayOfWeek.MONDAY
                "MONTHLY" -> date.dayOfMonth == 1
                "ONCE" -> d == 0 || d == 1
                else -> false
            }
            if (open) return at
        }
        return null
    }

    fun parseTime(hhmm: String): LocalTime? = runCatching {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        LocalTime.of(h, m)
    }.getOrNull()

    private fun delayUntil(t: LocalTime): Duration {
        val now = LocalDateTime.now()
        var at = LocalDateTime.of(LocalDate.now(), t)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return Duration.between(now, at)
    }

    const val CH_HIGH = "viro_reminders_high"
    const val CH_NORMAL = "viro_reminders"
    const val CH_LOW = "viro_reminders_low"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_HIGH, "Promises you made", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When something you said you'd do is due"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_NORMAL, "People who need your attention", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Check-ins, Loops and important dates"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LOW, "Achievements and suggestions", NotificationManager.IMPORTANCE_LOW),
        )
    }
}

/** Which reminder keys were already shown, and how many today — never nag twice. */
internal class NudgeLedger(context: Context) {
    private val prefs = context.getSharedPreferences("viro_nudge_ledger", Context.MODE_PRIVATE)

    fun seen(key: String) = prefs.contains("k:$key")

    fun mark(key: String) {
        prefs.edit().putLong("k:$key", System.currentTimeMillis()).apply()
        prune()
    }

    fun shownToday(): Int {
        val today = LocalDate.now().toString()
        return prefs.getInt("count:$today", 0)
    }

    fun countOne() {
        val today = LocalDate.now().toString()
        prefs.edit().putInt("count:$today", shownToday() + 1).apply()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600_000
        val edit = prefs.edit()
        prefs.all.forEach { (k, v) ->
            if (k.startsWith("k:") && (v as? Long ?: 0) < cutoff) edit.remove(k)
            if (k.startsWith("count:") && k != "count:${LocalDate.now()}") edit.remove(k)
        }
        edit.apply()
    }
}

internal object ReminderNotifications {
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openIntent(context: Context, target: String, subjectUserId: String? = null, requestCode: Int, conversationId: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN, target)
            subjectUserId?.let { putExtra(MainActivity.EXTRA_PEER_USER_ID, it) }
            conversationId?.let { putExtra(MainActivity.EXTRA_CONVERSATION_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun inQuietHours(start: String?, end: String?, now: LocalTime = LocalTime.now()): Boolean {
        val s = ReminderScheduler.parseTime(start ?: "21:30") ?: return false
        val e = ReminderScheduler.parseTime(end ?: "07:00") ?: return false
        return if (s <= e) !now.isBefore(s) && now.isBefore(e) else !now.isBefore(s) || now.isBefore(e)
    }

    fun post(context: Context, id: Int, channel: String, title: String, body: String, intent: PendingIntent, lines: List<String> = emptyList(), actions: List<NotificationCompat.Action> = emptyList()) {
        if (!canPost(context)) return
        ReminderScheduler.ensureChannels(context)
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_viro)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setColor(0xFF1565F5.toInt())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        if (lines.isNotEmpty()) {
            val style = NotificationCompat.InboxStyle().setSummaryText(body)
            lines.forEach { style.addLine(it) }
            b.setStyle(style)
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        actions.forEach { b.addAction(it) }
        runCatching { NotificationManagerCompat.from(context).notify(id, b.build()) }
    }
}

/**
 * The few-hourly check. Asks the server what matters now, then decides on the
 * phone what is worth an interruption: quiet hours, the daily cap, and never
 * the same reminder twice. Several people at once become one notification.
 */
class NudgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val session = SessionManager.get(applicationContext)
        if (!session.isAuthenticated) return Result.success()
        val res = runCatching { session.messagingApi.nudges() }.getOrElse { return Result.retry() }
        val s = res.settings
        if (ReminderNotifications.inQuietHours(s?.quietStart, s?.quietEnd)) return Result.success()
        val ledger = NudgeLedger(applicationContext)
        val fresh = res.nudges.orEmpty().filter { !ledger.seen(it.key) }

        // Promises due now go out on their own, uncapped: they are what the user asked for.
        fresh.filter { it.priority == "HIGH" }.forEach { n ->
            post(n, ReminderScheduler.CH_HIGH)
            ledger.mark(n.key)
        }

        val cap = s?.dailyCap ?: 2
        var room = cap - ledger.shownToday()
        val medium = fresh.filter { it.priority == "MEDIUM" }
        if (room > 0 && medium.isNotEmpty()) {
            if (medium.size == 1) {
                post(medium.first(), ReminderScheduler.CH_NORMAL)
            } else {
                ReminderNotifications.post(
                    applicationContext,
                    ID_ATTENTION,
                    ReminderScheduler.CH_NORMAL,
                    "${medium.size} people may need your attention",
                    medium.first().body,
                    ReminderNotifications.openIntent(applicationContext, MainActivity.OPEN_CONNECTIONS, requestCode = ID_ATTENTION),
                    lines = medium.take(5).map { it.body },
                )
            }
            medium.forEach { ledger.mark(it.key) }
            ledger.countOne()
            room--
        }
        val low = fresh.filter { it.priority == "LOW" }
        if (room > 0 && low.isNotEmpty()) {
            post(low.first(), ReminderScheduler.CH_LOW)
            low.forEach { ledger.mark(it.key) }
            ledger.countOne()
        }
        return Result.success()
    }

    private fun post(n: NudgeDto, channel: String) {
        val target = if (n.subjectUserId != null && n.kind != "ACHIEVEMENT") MainActivity.OPEN_CHAT else MainActivity.OPEN_CONNECTIONS
        ReminderNotifications.post(
            applicationContext,
            n.key.hashCode(),
            channel,
            n.title,
            n.body,
            ReminderNotifications.openIntent(applicationContext, target, n.subjectUserId, n.key.hashCode()),
        )
    }

    companion object {
        const val ID_ATTENTION = 7201
    }
}

/** The morning brief: one small summary instead of a string of nudges. */
class BriefWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val session = SessionManager.get(applicationContext)
        var briefTime = "08:00"
        if (session.isAuthenticated) {
            runCatching { session.messagingApi.nudges() }.onSuccess { res ->
                briefTime = res.settings?.briefTime ?: briefTime
                val brief = res.brief
                if (res.settings?.briefEnabled != false && brief != null && !brief.lines.isNullOrEmpty()) {
                    ReminderNotifications.post(
                        applicationContext,
                        ID_BRIEF,
                        ReminderScheduler.CH_NORMAL,
                        brief.title,
                        brief.summary,
                        ReminderNotifications.openIntent(applicationContext, MainActivity.OPEN_CONNECTIONS, requestCode = ID_BRIEF),
                        lines = brief.lines.orEmpty().map { "${it.icon ?: ""} ${it.name} — ${it.text}".trim() },
                    )
                    // What the brief covered is not repeated later today.
                    val ledger = NudgeLedger(applicationContext)
                    res.nudges.orEmpty().filter { it.priority == "MEDIUM" }.forEach { ledger.mark(it.key) }
                }
            }
        }
        ReminderScheduler.scheduleBrief(applicationContext, briefTime, true)
        return Result.success()
    }

    companion object {
        const val ID_BRIEF = 7200
    }
}

/** "You promised to call Mum at 19:00." — at the time, with Done and Later. */
class CommitmentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val session = SessionManager.get(applicationContext)
        if (!session.isAuthenticated) return Result.success()
        val res = runCatching { session.messagingApi.nudges() }.getOrElse { return Result.retry() }
        val s = res.settings
        if (ReminderNotifications.inQuietHours(s?.quietStart, s?.quietEnd)) {
            // Held until the quiet hours end rather than dropped.
            val end = ReminderScheduler.parseTime(s?.quietEnd ?: "07:00") ?: LocalTime.of(7, 0)
            var at = LocalDateTime.of(LocalDate.now(), end)
            if (!at.isAfter(LocalDateTime.now())) at = at.plusDays(1)
            val dto = com.viroreach.core.network.CommitmentDto(id, null, null, null, null, null, "", at.atZone(ZoneId.systemDefault()).toInstant().toString(), "OPEN", null, null)
            ReminderScheduler.scheduleCommitments(applicationContext, listOf(dto))
            return Result.success()
        }
        val nudge = res.nudges.orEmpty().firstOrNull { it.kind == "COMMITMENT" && it.key.startsWith("c:$id:") } ?: return Result.success()
        val ledger = NudgeLedger(applicationContext)
        if (ledger.seen(nudge.key)) return Result.success()
        ledger.mark(nudge.key)
        val done = PendingIntent.getBroadcast(
            applicationContext, id.hashCode() + 1,
            Intent(applicationContext, CommitmentActionReceiver::class.java).setAction(CommitmentActionReceiver.ACTION_DONE).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val later = PendingIntent.getBroadcast(
            applicationContext, id.hashCode() + 2,
            Intent(applicationContext, CommitmentActionReceiver::class.java).setAction(CommitmentActionReceiver.ACTION_LATER).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ReminderNotifications.post(
            applicationContext,
            id.hashCode(),
            ReminderScheduler.CH_HIGH,
            nudge.title,
            nudge.body,
            ReminderNotifications.openIntent(applicationContext, if (nudge.subjectUserId != null) MainActivity.OPEN_CHAT else MainActivity.OPEN_CONNECTIONS, nudge.subjectUserId, id.hashCode()),
            actions = listOf(
                NotificationCompat.Action(0, "Done", done),
                NotificationCompat.Action(0, "In an hour", later),
            ),
        )
        return Result.success()
    }
}

/** "Your Evening Check-In with Natasha is open." at the Loop's time. */
class LoopOpenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val session = SessionManager.get(applicationContext)
        if (session.isAuthenticated) {
            val loops = session.relationships.myLoops()
            val now = LocalDateTime.now()
            val ledger = NudgeLedger(applicationContext)
            for (loop in loops) {
                if (loop.active != true || loop.periodKey == null || loop.myAnswer != null) continue
                val time = ReminderScheduler.parseTime(loop.timeOfDay ?: "19:00") ?: continue
                val opened = LocalDateTime.of(now.toLocalDate(), time)
                if (now.isBefore(opened) || Duration.between(opened, now).toMinutes() > 90) continue
                val key = "loopopen:${loop.id}:${loop.periodKey}"
                if (ledger.seen(key)) continue
                ledger.mark(key)
                val peer = loop.participants.orEmpty().firstOrNull { it != session.tokenStore.getUserId() }
                val who = peer?.let { session.contactsRepository.displayNameForUserId(it) }
                ReminderNotifications.post(
                    applicationContext,
                    key.hashCode(),
                    ReminderScheduler.CH_NORMAL,
                    loop.title,
                    if (loop.answeredBy.orEmpty().isNotEmpty()) "${who ?: "They"} answered. Your turn — ${loop.prompt}"
                    else loop.prompt,
                    ReminderNotifications.openIntent(applicationContext, MainActivity.OPEN_CHAT, peer, key.hashCode()),
                )
            }
        }
        ReminderScheduler.scheduleLoopReminders(applicationContext)
        return Result.success()
    }
}

class CommitmentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = SessionManager.get(context).relationships
                when (intent.action) {
                    ACTION_DONE -> repo.setCommitmentStatus(id, "DONE")
                    ACTION_LATER -> repo.snoozeCommitment(id, Instant.now().plusSeconds(3600))
                }
                NotificationManagerCompat.from(context).cancel(id.hashCode())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.viroreach.app.COMMITMENT_DONE"
        const val ACTION_LATER = "com.viroreach.app.COMMITMENT_LATER"
    }
}
