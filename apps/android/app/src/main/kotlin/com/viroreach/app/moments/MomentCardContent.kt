package com.viroreach.app.moments

import com.viroreach.core.network.MomentDto
import java.time.Instant

/** Presentation of the existing Moment, without inferring feelings or availability. */
internal enum class MomentComposition { WORDS, COMPANY, ACTIVITY }

internal fun MomentDto.composition(): MomentComposition = when {
    type == "CUSTOM" -> MomentComposition.WORDS
    type == "WORKING" -> MomentComposition.ACTIVITY
    intent in listOf("BE", "TALK", "STAY") || (intent == null && type == "FREE") -> MomentComposition.COMPANY
    else -> MomentComposition.ACTIVITY
}

internal fun MomentDto.headline(): String = when (composition()) {
    MomentComposition.COMPANY -> invitationText?.takeIf { it.isNotBlank() } ?: activity()
    else -> activity()
}

internal fun MomentDto.cardContext(): String? = invitationText?.takeIf { it.isNotBlank() && it != headline() }

internal fun MomentDto.age(clock: Long): String {
    val created = runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrNull() ?: return "Active"
    val minutes = ((clock - created).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes == 0 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        else -> "${minutes / 60} h ago"
    }
}
