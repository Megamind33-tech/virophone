package com.viroreach.app.messaging.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Where an @name sits in a message. */
data class MentionSpan(val start: Int, val end: Int)

/**
 * Finds the @names in a message. A name may be two words ("@Mary Jane"), which
 * is why this stops at punctuation rather than at the first space, and an
 * address like name@example.com is never a mention.
 */
fun findMentions(text: String, knownNames: Collection<String> = emptyList()): List<MentionSpan> {
    val out = mutableListOf<MentionSpan>()
    var i = 0
    while (i < text.length) {
        val at = text.indexOf('@', i)
        if (at < 0) break
        if (at > 0 && !text[at - 1].isWhitespace()) {
            i = at + 1
            continue
        }
        // Prefer a known member's name, longest first, so "@Mary Jane" beats "@Mary".
        val matched = knownNames
            .filter { it.isNotBlank() && text.regionMatches(at + 1, it, 0, it.length, ignoreCase = true) }
            .maxByOrNull { it.length }
        val end = if (matched != null) {
            at + 1 + matched.length
        } else {
            var j = at + 1
            while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '.')) j++
            j
        }
        if (end > at + 1) out += MentionSpan(at, end)
        i = end
    }
    return out
}

/** The message text with every @name in the chat's accent colour. */
fun withMentionsHighlighted(
    text: String,
    accent: Color,
    knownNames: Collection<String> = emptyList(),
): AnnotatedString {
    val spans = findMentions(text, knownNames)
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        for (span in spans) {
            if (span.start > cursor) append(text.substring(cursor, span.start))
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) {
                append(text.substring(span.start, span.end))
            }
            cursor = span.end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
