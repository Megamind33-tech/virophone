package com.viroreach.app.messaging.ui

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Viro's own animated stickers. Built from emoji and motion rather than image
 * files: they cost no data to send (the message carries only "pack/id"), look
 * sharp at any size, and every phone already has the glyphs.
 */
enum class StickerMotion { BOUNCE, PULSE, WIGGLE, FLOAT, SPIN, SHAKE }

data class Sticker(val pack: String, val id: String, val emoji: String, val motion: StickerMotion, val label: String)

data class StickerPack(val id: String, val title: String, val stickers: List<Sticker>)

val STICKER_PACKS: List<StickerPack> = listOf(
    StickerPack(
        "love", "Love",
        listOf(
            Sticker("love", "heart", "❤️", StickerMotion.PULSE, "Love"),
            Sticker("love", "hug", "🥰", StickerMotion.BOUNCE, "Hug"),
            Sticker("love", "kiss", "😘", StickerMotion.WIGGLE, "Kiss"),
            Sticker("love", "letter", "💌", StickerMotion.FLOAT, "Love letter"),
            Sticker("love", "flowers", "💐", StickerMotion.WIGGLE, "Flowers"),
            Sticker("love", "hands", "🫶", StickerMotion.PULSE, "Heart hands"),
            Sticker("love", "miss", "🥺", StickerMotion.SHAKE, "Miss you"),
            Sticker("love", "sparkle", "💖", StickerMotion.SPIN, "Sparkling heart"),
        ),
    ),
    StickerPack(
        "laughs", "Laughs",
        listOf(
            Sticker("laughs", "lol", "😂", StickerMotion.SHAKE, "Laughing"),
            Sticker("laughs", "rofl", "🤣", StickerMotion.SPIN, "Rolling"),
            Sticker("laughs", "cat", "😹", StickerMotion.BOUNCE, "Cat laughing"),
            Sticker("laughs", "wink", "😜", StickerMotion.WIGGLE, "Cheeky"),
            Sticker("laughs", "dead", "💀", StickerMotion.SHAKE, "I'm dead"),
            Sticker("laughs", "clown", "🤡", StickerMotion.BOUNCE, "Clown"),
        ),
    ),
    StickerPack(
        "everyday", "Everyday",
        listOf(
            Sticker("everyday", "wave", "👋", StickerMotion.WIGGLE, "Hello"),
            Sticker("everyday", "thanks", "🙏", StickerMotion.PULSE, "Thank you"),
            Sticker("everyday", "ok", "👍", StickerMotion.BOUNCE, "OK"),
            Sticker("everyday", "party", "🎉", StickerMotion.SHAKE, "Celebrate"),
            Sticker("everyday", "coffee", "☕", StickerMotion.FLOAT, "Coffee"),
            Sticker("everyday", "sleep", "😴", StickerMotion.FLOAT, "Good night"),
            Sticker("everyday", "sun", "🌞", StickerMotion.SPIN, "Good morning"),
            Sticker("everyday", "onmyway", "🏃", StickerMotion.BOUNCE, "On my way"),
            Sticker("everyday", "think", "🤔", StickerMotion.WIGGLE, "Thinking"),
            Sticker("everyday", "strong", "💪", StickerMotion.PULSE, "Strong"),
        ),
    ),
    StickerPack(
        "zambia", "Zambia",
        listOf(
            Sticker("zambia", "flag", "🇿🇲", StickerMotion.WIGGLE, "Zambia"),
            Sticker("zambia", "eagle", "🦅", StickerMotion.FLOAT, "Fish eagle"),
            Sticker("zambia", "falls", "🌊", StickerMotion.FLOAT, "Mosi-oa-Tunya"),
            Sticker("zambia", "football", "⚽", StickerMotion.SPIN, "Chipolopolo"),
            Sticker("zambia", "copper", "🥉", StickerMotion.PULSE, "Copper"),
            Sticker("zambia", "africa", "🌍", StickerMotion.SPIN, "Africa"),
            Sticker("zambia", "drum", "🥁", StickerMotion.SHAKE, "Drum"),
            Sticker("zambia", "maize", "🌽", StickerMotion.BOUNCE, "Maize"),
        ),
    ),
    StickerPack(
        "work", "Work",
        listOf(
            Sticker("work", "done", "✅", StickerMotion.PULSE, "Done"),
            Sticker("work", "onit", "🫡", StickerMotion.BOUNCE, "On it"),
            Sticker("work", "deal", "🤝", StickerMotion.PULSE, "Deal"),
            Sticker("work", "rocket", "🚀", StickerMotion.FLOAT, "Shipped"),
            Sticker("work", "clap", "👏", StickerMotion.SHAKE, "Well done"),
            Sticker("work", "eyes", "👀", StickerMotion.WIGGLE, "Looking"),
            Sticker("work", "time", "⏰", StickerMotion.SHAKE, "Reminder"),
            Sticker("work", "idea", "💡", StickerMotion.PULSE, "Idea"),
        ),
    ),
)

fun findSticker(pack: String?, id: String?): Sticker? =
    STICKER_PACKS.firstOrNull { it.id == pack }?.stickers?.firstOrNull { it.id == id }

/** A sticker, animated. [size] is the glyph box; motion stays inside it. */
@Composable
fun AnimatedSticker(sticker: Sticker, size: Dp = 120.dp, animate: Boolean = true) {
    val t = rememberInfiniteTransition(label = "sticker")
    val phase by if (animate) {
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (sticker.motion == StickerMotion.SPIN) 2400 else 900, easing = if (sticker.motion == StickerMotion.SPIN) LinearEasing else FastOutSlowInEasing), if (sticker.motion == StickerMotion.SPIN) RepeatMode.Restart else RepeatMode.Reverse), label = "p")
    } else {
        t.animateFloat(0f, 0f, infiniteRepeatable(tween(1000)), label = "still")
    }
    val m = when (sticker.motion) {
        StickerMotion.BOUNCE -> Modifier.offset(y = (-(size.value * 0.08f) * phase).dp)
        StickerMotion.PULSE -> Modifier.scale(1f + 0.12f * phase)
        StickerMotion.WIGGLE -> Modifier.rotate(-10f + 20f * phase)
        StickerMotion.FLOAT -> Modifier.offset(y = (-(size.value * 0.05f) * phase).dp).rotate(-4f + 8f * phase)
        StickerMotion.SPIN -> Modifier.rotate(360f * phase)
        StickerMotion.SHAKE -> Modifier.offset(x = ((size.value * 0.04f) * (phase - 0.5f) * 2).dp).rotate(-6f + 12f * phase)
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Text(sticker.emoji, fontSize = (size.value * 0.62f).sp, modifier = m)
    }
}

/** The last stickers sent, first in the picker. */
object RecentStickers {
    private const val KEY = "recent_stickers"

    fun get(context: Context): List<Sticker> =
        context.getSharedPreferences("viro_stickers", Context.MODE_PRIVATE).getString(KEY, "")!!
            .split(',').filter { it.contains('/') }
            .mapNotNull { findSticker(it.substringBefore('/'), it.substringAfter('/')) }

    fun add(context: Context, s: Sticker) {
        val list = (listOf(s) + get(context).filter { it != s }).take(12)
        context.getSharedPreferences("viro_stickers", Context.MODE_PRIVATE).edit()
            .putString(KEY, list.joinToString(",") { "${it.pack}/${it.id}" }).apply()
    }
}
