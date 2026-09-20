package com.viroreach.app.people

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * "last seen today at 14:20", "last seen yesterday at 21:05", "last seen on
 * 3 Sep". Null when the person doesn't share it, so nothing is shown at all
 * rather than a guess.
 */
fun lastSeenLabel(atMs: Long?, nowMs: Long = System.currentTimeMillis()): String? {
    if (atMs == null || atMs <= 0) return null
    val minutes = (nowMs - atMs) / 60_000
    if (minutes < 1) return "last seen just now"
    if (minutes < 60) return "last seen $minutes ${if (minutes == 1L) "minute" else "minutes"} ago"

    val then = Calendar.getInstance().apply { timeInMillis = atMs }
    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val yesterday = Calendar.getInstance().apply { timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, -1) }
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(atMs))
    return when {
        sameDay(then, now) -> "last seen today at $time"
        sameDay(then, yesterday) -> "last seen yesterday at $time"
        then.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
            "last seen on " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(atMs))
        else -> "last seen on " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(atMs))
    }
}

/** The three privacy choices, in the words the settings screen shows. */
val VISIBILITY_LABELS: List<Pair<String, String>> = listOf(
    "EVERYONE" to "Everyone",
    "CONTACTS" to "My contacts",
    "NOBODY" to "Nobody",
)

fun visibilityLabel(value: String?): String =
    VISIBILITY_LABELS.firstOrNull { it.first == (value ?: "EVERYONE").uppercase() }?.second ?: "Everyone"
