package com.viroreach.app.relationships

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Spots promises in the user's own outgoing messages — "I'll call you
 * tomorrow", "I'll send the documents Friday", "Let's meet next Tuesday" —
 * and turns them into a suggestion. It never creates anything by itself: the
 * chat shows a small chip and the user decides.
 *
 * Deliberately conservative. A missed promise costs nothing (the user can
 * add one by hand); a false one is noise, and noise is what makes people turn
 * reminders off.
 */
object CommitmentDetector {

    enum class Kind { CALL, SEND, MEET, FOLLOW_UP, OTHER }

    data class Suggestion(
        val kind: Kind,
        /** What was promised, as a verb phrase: "send the documents". */
        val action: String,
        val due: LocalDateTime,
        /** The chip text: "Remind me tomorrow", "Add commitment", "Add to plans". */
        val chip: String,
        /** Human "when": "tomorrow", "Friday", "at 17:00". */
        val whenLabel: String,
    )

    private val INTENT = Regex(
        """\b(i'll|i will|i'm going to|im going to|i am going to|i shall|let me|i'll try to|i promise to|i can)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val LETS = Regex("""\b(let's|lets|let us)\s+(meet|see each other|catch up|talk|link up|have)\b""", RegexOption.IGNORE_CASE)

    private val CALL_WORDS = Regex("""^(call|phone|ring|give you a call|voice call)\b""", RegexOption.IGNORE_CASE)
    private val SEND_WORDS = Regex("""^(send|share|email|forward|pay|deliver|submit|transfer|drop off|bring)\b""", RegexOption.IGNORE_CASE)
    private val MEET_WORDS = Regex("""^(meet|see you|visit|come|pass by|pick you up)\b""", RegexOption.IGNORE_CASE)
    private val FOLLOW_WORDS = Regex("""^(follow up|get back|reply|respond|check|confirm|update you|let you know|text you|message you)\b""", RegexOption.IGNORE_CASE)

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    private data class When(val due: LocalDateTime, val label: String, val range: IntRange)

    fun detect(text: String, now: LocalDateTime = LocalDateTime.now()): Suggestion? {
        val clean = text.replace('’', '\'').trim()
        if (clean.length < 8 || clean.endsWith("?")) return null

        val lets = LETS.find(clean)
        val intent = INTENT.find(clean)
        if (lets == null && intent == null) return null

        val time = findWhen(clean, now) ?: return null
        if (!time.due.isAfter(now.minusMinutes(1))) return null

        val (kind, action) = if (lets != null && (intent == null || lets.range.first < intent.range.first)) {
            Kind.MEET to "meet"
        } else {
            val after = clean.substring(intent!!.range.last + 1)
            val phrase = actionPhrase(after, time, intent.range.last + 1)
            val k = when {
                CALL_WORDS.containsMatchIn(phrase) -> Kind.CALL
                SEND_WORDS.containsMatchIn(phrase) -> Kind.SEND
                MEET_WORDS.containsMatchIn(phrase) -> Kind.MEET
                FOLLOW_WORDS.containsMatchIn(phrase) -> Kind.FOLLOW_UP
                else -> Kind.OTHER
            }
            k to phrase
        }
        if (action.isBlank()) return null

        val chip = when {
            kind == Kind.MEET -> "Add to plans"
            kind == Kind.CALL && time.label == "tomorrow" -> "Remind me tomorrow"
            kind == Kind.CALL -> "Remind me ${time.label}"
            else -> "Add commitment"
        }
        return Suggestion(kind, action, time.due, chip, time.label)
    }

    /** "send the documents Friday." → "send the documents" */
    private fun actionPhrase(after: String, time: When, offset: Int): String {
        val timeStartInAfter = time.range.first - offset
        var s = if (timeStartInAfter in 1..after.length) after.substring(0, timeStartInAfter) else after
        s = s.split(Regex("""[.!,;\n]""")).first()
        s = s.replace(Regex("""\b(you|u)\b\s*$""", RegexOption.IGNORE_CASE), "you")
        s = s.replace(Regex("""\s+(by|on|at|this|next|in|before)\s*$""", RegexOption.IGNORE_CASE), "")
        return s.trim().take(120)
    }

    private fun findWhen(text: String, now: LocalDateTime): When? {
        val lower = text.lowercase()
        val today = now.toLocalDate()
        val candidates = mutableListOf<When>()

        fun at(date: LocalDate, t: LocalTime, label: String, m: MatchResult) =
            candidates.add(When(LocalDateTime.of(date, t), label, m.range))

        Regex("""\btomorrow\s+(morning|afternoon|evening|night)\b""").find(lower)?.let {
            val t = partOfDay(it.groupValues[1])
            at(today.plusDays(1), t, "tomorrow ${it.groupValues[1]}", it)
        }
        Regex("""\btomorrow\b""").find(lower)?.let { at(today.plusDays(1), LocalTime.of(9, 0), "tomorrow", it) }
        Regex("""\b(tonight|this evening)\b""").find(lower)?.let { at(today, LocalTime.of(19, 0), "this evening", it) }
        Regex("""\bthis afternoon\b""").find(lower)?.let { at(today, LocalTime.of(14, 0), "this afternoon", it) }
        Regex("""\b(later today|later)\b""").find(lower)?.let {
            candidates.add(When(now.plusHours(3).withSecond(0).withNano(0), "later today", it.range))
        }
        Regex("""\btoday\b""").find(lower)?.let {
            val due = if (now.toLocalTime().isBefore(LocalTime.of(17, 0))) LocalDateTime.of(today, LocalTime.of(17, 0)) else now.plusHours(1)
            candidates.add(When(due.withSecond(0).withNano(0), "today", it.range))
        }
        Regex("""\bnext week\b""").find(lower)?.let {
            at(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), LocalTime.of(9, 0), "next week", it)
        }
        Regex("""\b(next\s+|on\s+|this\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""").find(lower)?.let {
            val dow = WEEKDAYS.getValue(it.groupValues[2])
            val forceNext = it.groupValues[1].trim() == "next"
            var date = today.with(TemporalAdjusters.nextOrSame(dow))
            if (date == today && now.toLocalTime().isAfter(LocalTime.of(17, 0))) date = date.plusWeeks(1)
            if (forceNext && date.isBefore(today.plusDays(2))) date = date.plusWeeks(1)
            at(date, LocalTime.of(9, 0), it.groupValues[2].replaceFirstChar { c -> c.uppercase() }, it)
        }
        Regex("""\bin\s+(\d{1,2}|an?|one|two|three)\s+(minutes?|mins?|hours?|hrs?|days?)\b""").find(lower)?.let {
            val n = when (val raw = it.groupValues[1]) {
                "a", "an", "one" -> 1L
                "two" -> 2L
                "three" -> 3L
                else -> raw.toLong()
            }
            val unit = it.groupValues[2]
            val due = when {
                unit.startsWith("min") -> now.plusMinutes(n)
                unit.startsWith("h") -> now.plusHours(n)
                else -> now.plusDays(n).with(LocalTime.of(9, 0))
            }
            candidates.add(When(due.withSecond(0).withNano(0), it.value.trim(), it.range))
        }

        // An explicit clock time refines whichever day was found (or today).
        val clock = Regex("""\bat\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|h)?\b""").find(lower)
        val base = candidates.minByOrNull { it.range.first }
        if (clock != null) {
            var hour = clock.groupValues[1].toInt()
            val minute = clock.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val suffix = clock.groupValues[3]
            if (suffix == "pm" && hour < 12) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
            // "at 5" with no am/pm means the afternoon for most arrangements.
            if (suffix.isEmpty() && hour in 1..7) hour += 12
            if (hour !in 0..23 || minute !in 0..59) return base
            val date = base?.due?.toLocalDate() ?: today
            var due = LocalDateTime.of(date, LocalTime.of(hour, minute))
            if (base == null && due.isBefore(now)) due = due.plusDays(1)
            val label = (base?.label?.let { "$it " } ?: "") + "at %02d:%02d".format(hour, minute)
            val start = minOf(base?.range?.first ?: clock.range.first, clock.range.first)
            return When(due, label, start..clock.range.last)
        }
        return base
    }

    private fun partOfDay(p: String): LocalTime = when (p) {
        "morning" -> LocalTime.of(9, 0)
        "afternoon" -> LocalTime.of(14, 0)
        "evening" -> LocalTime.of(19, 0)
        else -> LocalTime.of(20, 30)
    }
}
