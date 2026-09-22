package com.viroreach.app.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.viroreach.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on the phone so it can be shared on the next launch.
 * Testers' phones aren't attached to a debugger — without this a crash is
 * just "it closed", with no way to tell which line did it.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    private const val STEP = "last_step.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(report(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
        runCatching { File(context.filesDir, STEP).delete() }
    }

    /**
     * Marks that the app is inside something risky, on disk.
     *
     * The breadcrumbs above live in memory and die with the process, which is
     * no help at all for a crash below the JVM — a native one takes the
     * handler with it and leaves nothing. This is written before entering and
     * deleted on the way out, so a note still sitting there at the next launch
     * means the app never came out of that step.
     */
    fun enter(context: Context, step: String) {
        val app = context.applicationContext
        runCatching {
            File(app.filesDir, STEP).writeText(
                buildString {
                    appendLine("Viro ${BuildConfig.VERSION_NAME} closed inside: $step")
                    appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
                    appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine()
                    appendLine("No Java stack trace, which means the process died below the JVM —")
                    appendLine("usually a native library. The step above is where it was.")
                    appendLine()
                    append(runCatching { Breadcrumbs.dump() }.getOrDefault("(no breadcrumbs)"))
                },
            )
        }
    }

    /** Came out the other side. */
    fun left(context: Context) {
        runCatching { File(context.applicationContext.filesDir, STEP).delete() }
    }

    /**
     * About to try something that has taken the app down before.
     *
     * A native crash runs no handler, so nothing can be caught and nothing is
     * written on the way out. The only way to learn from one is to write the
     * intention down *first*: if this file is still here next time, the last
     * attempt did not survive.
     */
    fun attempting(context: Context, key: String) {
        runCatching { File(context.applicationContext.filesDir, "try_$key").writeText("1") }
    }

    /** It worked. The next launch is free to try again. */
    fun survived(context: Context, key: String) {
        runCatching { File(context.applicationContext.filesDir, "try_$key").delete() }
    }

    /**
     * True when a previous attempt was started and never finished, which for
     * something with native code means it is what killed the app.
     */
    fun isUnsafe(context: Context, key: String): Boolean =
        runCatching { File(context.applicationContext.filesDir, "try_$key").exists() }.getOrDefault(false)

    /**
     * Viro is no longer on screen, so any step it is "inside" stops counting.
     *
     * Android reclaims backgrounded apps whenever it wants the memory, and on
     * a phone with little of it that is routine rather than a fault. Without
     * this, every such reclaim left a note behind and came back next launch
     * claiming the app had died inside whatever screen happened to be open —
     * a crash report for something that never crashed. A real native crash
     * happens while the app is in front, where this has not run.
     */
    fun backgrounded(context: Context) {
        runCatching { File(context.applicationContext.filesDir, STEP).delete() }
    }

    /**
     * Why something fell back to its plain version.
     *
     * Kept next to the crash notes because it answers the same question and
     * gets read at the same time. Without it a fallback is invisible: the
     * screen just quietly looks cheaper than it should and nobody can say why.
     */
    fun noteReason(context: Context, key: String, reason: String) {
        runCatching { File(context.applicationContext.filesDir, "why_$key").writeText(reason) }
    }

    /** What was written down last time [key] fell back, if anything. */
    fun reason(context: Context, key: String): String? =
        runCatching {
            File(context.applicationContext.filesDir, "why_$key").takeIf { it.exists() }?.readText()
        }.getOrNull()

    /** Forget that [key] ever failed, so it is tried from scratch. */
    fun forget(context: Context, key: String) {
        val dir = context.applicationContext.filesDir
        runCatching { File(dir, "try_$key").delete() }
        runCatching { File(dir, "why_$key").delete() }
    }

    /** A step the app went into and never came out of. */
    fun unfinished(context: Context): String? =
        runCatching { File(context.filesDir, STEP).takeIf { it.exists() }?.readText() }.getOrNull()

    private fun report(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("Viro ${BuildConfig.VERSION_NAME} crash report")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(runCatching { Breadcrumbs.dump() }.getOrDefault("(no breadcrumbs)"))
            appendLine()
            // Share sheets and chat apps choke on huge texts; the top of the
            // trace and its causes are what identify the bug.
            append(trace.take(12_000))
        }
    }
}

/** After a crash: offer to send the report, once. */
@Composable
fun CrashReportPrompt() {
    val context = LocalContext.current
    // A Java crash if there is one, otherwise a step the app never came out
    // of — which is what a native crash leaves behind.
    var report by remember {
        mutableStateOf(CrashReporter.pending(context) ?: CrashReporter.unfinished(context))
    }
    val text = report ?: return
    AlertDialog(
        onDismissRequest = {
            CrashReporter.clear(context)
            report = null
        },
        title = { Text("Viro closed unexpectedly") },
        text = { Text("Sending the crash report helps us fix it. It contains the app version, phone model and where the error happened — no messages or contacts.") },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Viro crash report")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                runCatching { context.startActivity(Intent.createChooser(send, "Send crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                CrashReporter.clear(context)
                report = null
            }) { Text("Send report") }
        },
        dismissButton = {
            TextButton(onClick = {
                CrashReporter.clear(context)
                report = null
            }) { Text("Not now") }
        },
    )
}
