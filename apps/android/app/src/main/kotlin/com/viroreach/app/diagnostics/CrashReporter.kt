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
    }

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
    var report by remember { mutableStateOf(CrashReporter.pending(context)) }
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
