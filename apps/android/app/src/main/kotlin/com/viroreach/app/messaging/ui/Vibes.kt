package com.viroreach.app.messaging.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viroreach.core.designsystem.ViroColors

/**
 * A chat's Vibe: how the conversation looks and what it offers, chosen per
 * person and private to the chooser. The engine underneath is the same; the
 * experience fits the relationship — warm and intimate with a partner,
 * calm and precise with a client.
 */
enum class Vibe(
    val key: String,
    val label: String,
    val description: String,
    val accent: Color,
    val mine: Color,
    val theirs: Color,
    val corner: Dp,
    val quickReactions: List<String>,
    /** "Thinking of you" heartbeat tap in the header. */
    val heartbeat: Boolean,
    /** Bubble effects (confetti) offered when sending. */
    val effects: Boolean,
    /** Big, easy mic button for voice-first chats. */
    val voiceFirst: Boolean,
) {
    CLOSE(
        "CLOSE", "Close", "Warm and private, for a partner or best friend",
        Color(0xFFFF6F91), Color(0xFFC93A63), Color(0xFF3A1D33), 22.dp,
        listOf("❤️", "🥰", "😘", "😂", "🥺", "🔥"), heartbeat = true, effects = true, voiceFirst = false,
    ),
    FAMILY(
        "FAMILY", "Family", "Warm and voice-first, easy for everyone",
        Color(0xFFFFB35C), Color(0xFFC9711F), Color(0xFF3A2A17), 18.dp,
        listOf("❤️", "🙏", "😂", "👍", "😮", "🙌"), heartbeat = true, effects = true, voiceFirst = true,
    ),
    FRIENDS(
        "FRIENDS", "Friends", "Playful, with effects and any reaction",
        Color(0xFFA28BFF), Color(0xFF6A4DF0), Color(0xFF262049), 20.dp,
        listOf("😂", "🔥", "💯", "😮", "❤️", "👀"), heartbeat = false, effects = true, voiceFirst = false,
    ),
    WORK(
        "WORK", "Work", "Calm, compact and precise",
        Color(0xFF6FA8FF), Color(0xFF2B5FC4), Color(0xFF17263A), 10.dp,
        listOf("👍", "✅", "👀", "🙏", "👏", "❓"), heartbeat = false, effects = false, voiceFirst = false,
    ),
    DEFAULT(
        "DEFAULT", "Classic", "Viro's standard look",
        ViroColors.ElectricBlue, ViroColors.ElectricBlue, ViroColors.NavySurfaceElevated, 16.dp,
        listOf("👍", "❤️", "😂", "😮", "😢", "🙏"), heartbeat = false, effects = true, voiceFirst = false,
    );

    companion object {
        fun of(key: String?): Vibe = values().firstOrNull { it.key == key } ?: DEFAULT
        val choosable = listOf(CLOSE, FAMILY, FRIENDS, WORK)
    }
}

/** A wider set for "react with anything". */
val ALL_REACTIONS = listOf(
    "❤️", "😂", "😮", "😢", "🙏", "👍", "🔥", "🥰", "😘", "🥺", "💯", "👀",
    "✅", "👏", "🙌", "🎉", "😅", "🤣", "😍", "😎", "🤔", "😴", "😡", "🤯",
    "💙", "💛", "💚", "💜", "🤍", "💪", "🤝", "✨", "🌹", "☕", "🍀", "⚡",
    "👌", "✌️", "🤞", "🫶", "🙃", "😇", "🥳", "😬", "🙈", "💔", "❓", "‼️",
)
