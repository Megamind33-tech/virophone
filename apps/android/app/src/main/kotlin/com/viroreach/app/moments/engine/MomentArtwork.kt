package com.viroreach.app.moments.engine

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * A picture for each thing people can do together.
 *
 * Choosing an activity used to be a column of identical grey rectangles with
 * words in them, which told you nothing and felt like a settings screen. A
 * Moment is the opposite of a settings screen: it should look like somewhere
 * you want to be before you have finished reading the label.
 *
 * An activity shows a bundled photograph when one exists for it, and a drawn
 * picture otherwise. Both are wanted: a photograph says more in a glance, and
 * the drawing costs nothing to download, scales to any card without a folder
 * of densities, and is there for an activity no artwork has been made for yet.
 *
 * Either way the picture carries the room's own colours, so the card is a
 * glimpse of the room it opens — the cinema card is already dark, the kitchen
 * card is already warm, and walking into the room feels like the same place
 * rather than a different app.
 *
 * The drawn motifs are geometric on purpose. Illustrated food and smiling
 * faces age badly and belong to somebody else's brand; shapes made of light
 * belong to this one.
 */
data class ActivityArt(
    /** The ground the motif sits on, echoing the scene the room will open in. */
    val top: Color,
    val bottom: Color,
    /** The light the motif is drawn in. */
    val ink: Color,
    /** How much the art breathes; quiet activities barely move. */
    val motion: Float,
    /** Draws the motif. [phase] runs 0..1 and back, for subtle life. */
    val draw: DrawScope.(phase: Float) -> Unit,
)

/**
 * The picture for an activity. Unknown keys get the calm one rather than
 * nothing, so an activity added on the server before this build knows about it
 * still looks like something.
 */
fun activityArt(key: String): ActivityArt = when (key) {
    "BE" -> ActivityArt(Color(0xFF14243F), Color(0xFF0A1830), Color(0xFF9EC5FF), 0.3f) { p -> drawTogether(p) }
    "TALK" -> ActivityArt(Color(0xFF13283C), Color(0xFF071A2B), Color(0xFF8FD4FF), 0.6f) { p -> drawMic(p) }
    "WATCH" -> ActivityArt(Color(0xFF0E0F1D), Color(0xFF05060C), Color(0xFFBFC6FF), 0.25f) { p -> drawCinema(p) }
    "LISTEN" -> ActivityArt(Color(0xFF211434), Color(0xFF0E0819), Color(0xFFD9A8FF), 0.8f) { p -> drawRecord(p) }
    "COOK" -> ActivityArt(Color(0xFF2E1A10), Color(0xFF150C08), Color(0xFFFFC08A), 0.5f) { p -> drawPan(p) }
    "WALK" -> ActivityArt(Color(0xFF122A20), Color(0xFF07150F), Color(0xFF9BE6BE), 0.35f) { p -> drawTrail(p) }
    "CHOOSE" -> ActivityArt(Color(0xFF1B1F3A), Color(0xFF0A0C1B), Color(0xFFAFC0FF), 0.4f) { p -> drawChoice(p) }
    "LEARN" -> ActivityArt(Color(0xFF14212E), Color(0xFF080F17), Color(0xFFA9D8FF), 0.3f) { p -> drawBook(p) }
    "CELEBRATE" -> ActivityArt(Color(0xFF2B1E08), Color(0xFF140E04), Color(0xFFFFD68A), 0.9f) { p -> drawCelebration(p) }
    "REMEMBER" -> ActivityArt(Color(0xFF2A1618), Color(0xFF130A0B), Color(0xFFFFB3B8), 0.25f) { p -> drawPhoto(p) }
    "STAY" -> ActivityArt(Color(0xFF0E1526), Color(0xFF060A13), Color(0xFFBBD0F0), 0.12f) { p -> drawNight(p) }
    else -> ActivityArt(Color(0xFF13203A), Color(0xFF080F1C), Color(0xFF9EC5FF), 0.2f) { p -> drawTogether(p) }
}

/**
 * A photograph for an activity, once one has been bundled for it.
 *
 * Empty until real artwork is commissioned: adding a picture is one line here
 * and one file in the drawable folders, and nothing else changes. Named
 * resource ids rather than a lookup by string so resource shrinking cannot
 * quietly remove them from a release build.
 *
 * Anything without an entry keeps the drawn artwork, which is why this can
 * fill up one activity at a time instead of all at once — and why a new
 * activity always looks like something the day it is added.
 */
private val activityPhotos: Map<String, Int> = emptyMap()

@Composable
fun MomentActivityArt(intentKey: String, modifier: Modifier = Modifier) {
    val photo = activityPhotos[intentKey]
    if (photo != null) {
        Box(modifier) {
            Image(
                painter = painterResource(photo),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            // The same scrim the drawn art carries, so a label stays readable
            // over a bright photograph as well as a dark one.
            Canvas(Modifier.matchParentSize()) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.10f),
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.62f),
                    ),
                )
            }
        }
    } else {
        DrawnActivityArt(intentKey, modifier)
    }
}

/**
 * The drawn picture: what an activity looks like when no photograph has been
 * bundled for it, and what it falls back to for anything the server names that
 * this build has never heard of.
 *
 * It breathes gently, and honours the phone's animation setting — somebody who
 * has turned animations off gets the same picture, held still.
 */
@Composable
private fun DrawnActivityArt(intentKey: String, modifier: Modifier = Modifier) {
    val art = activityArt(intentKey)
    val context = LocalContext.current
    val stillness = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val breath = rememberInfiniteTransition(label = "artBreath")
    val raw by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween((7000 / (0.3f + art.motion)).toInt(), easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "artPhase",
    )
    val phase = if (stillness) 0.5f else raw

    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(art.top, art.bottom)))
        // A low glow, so the ground is never a flat block of colour.
        drawRect(
            Brush.radialGradient(
                colors = listOf(art.ink.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.72f, size.height * 0.28f),
                radius = size.maxDimension * 0.8f,
            ),
        )
        art.draw(this, phase)
        // Keeps a label readable over any of them.
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.45f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.55f),
            ),
        )
    }
}

// --------------------------------------------------------------- the motifs
//
// Each draws into whatever box it is given, in units of the smaller side, so
// the same motif works on a wide feed card and a square picker tile.

/** Two people, overlapping. The whole product in one shape. */
private fun DrawScope.drawTogether(phase: Float) {
    val u = size.minDimension
    val ink = Color(0xFF9EC5FF)
    val drift = (phase - 0.5f) * u * 0.05f
    val cy = size.height * 0.52f
    // Behind, and slightly away.
    translate(left = -u * 0.14f + drift, top = 0f) {
        drawCircle(ink.copy(alpha = 0.30f), radius = u * 0.13f, center = Offset(size.width * 0.5f, cy - u * 0.14f))
        drawArc(
            color = ink.copy(alpha = 0.30f),
            startAngle = 180f, sweepAngle = 180f, useCenter = true,
            topLeft = Offset(size.width * 0.5f - u * 0.24f, cy + u * 0.02f),
            size = Size(u * 0.48f, u * 0.42f),
        )
    }
    // In front.
    translate(left = u * 0.14f - drift, top = 0f) {
        drawCircle(ink.copy(alpha = 0.72f), radius = u * 0.15f, center = Offset(size.width * 0.5f, cy - u * 0.12f))
        drawArc(
            color = ink.copy(alpha = 0.72f),
            startAngle = 180f, sweepAngle = 180f, useCenter = true,
            topLeft = Offset(size.width * 0.5f - u * 0.27f, cy + u * 0.05f),
            size = Size(u * 0.54f, u * 0.46f),
        )
    }
}

/** A microphone, with the room listening. */
private fun DrawScope.drawMic(phase: Float) {
    val u = size.minDimension
    val ink = Color(0xFF8FD4FF)
    val cx = size.width * 0.5f
    val cy = size.height * 0.46f
    // Capsule.
    drawRoundRect(
        color = ink.copy(alpha = 0.85f),
        topLeft = Offset(cx - u * 0.09f, cy - u * 0.24f),
        size = Size(u * 0.18f, u * 0.34f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.09f),
    )
    // Cradle and stem.
    drawArc(
        color = ink.copy(alpha = 0.7f), startAngle = 0f, sweepAngle = 180f, useCenter = false,
        topLeft = Offset(cx - u * 0.17f, cy - u * 0.10f), size = Size(u * 0.34f, u * 0.30f),
        style = Stroke(width = u * 0.035f),
    )
    drawLine(
        ink.copy(alpha = 0.7f),
        Offset(cx, cy + u * 0.20f), Offset(cx, cy + u * 0.30f),
        strokeWidth = u * 0.035f,
    )
    // Sound, arriving in waves that keep time.
    for (i in 1..3) {
        val reach = u * (0.26f + i * 0.10f)
        val alpha = (0.32f - i * 0.07f) * (0.5f + phase * 0.9f)
        for (side in listOf(-1f, 1f)) {
            drawArc(
                color = ink.copy(alpha = alpha.coerceIn(0f, 1f)),
                startAngle = if (side < 0) 120f else -60f, sweepAngle = 120f, useCenter = false,
                topLeft = Offset(cx - reach, cy - reach), size = Size(reach * 2, reach * 2),
                style = Stroke(width = u * 0.022f),
            )
        }
    }
}

/** A screen in the dark, and the light coming off it. */
private fun DrawScope.drawCinema(phase: Float) {
    val u = size.minDimension
    val ink = Color(0xFFBFC6FF)
    val w = size.width * 0.62f
    val h = w * 0.56f
    val left = (size.width - w) / 2f
    val top = size.height * 0.30f
    // The beam, before the screen, so the screen sits on top of it.
    val beam = Path().apply {
        moveTo(left + w * 0.5f, top + h * 0.5f)
        lineTo(left - w * 0.35f, size.height)
        lineTo(left + w * 1.35f, size.height)
        close()
    }
    drawPath(beam, Brush.verticalGradient(
        listOf(ink.copy(alpha = 0.16f + phase * 0.07f), Color.Transparent),
        startY = top, endY = size.height,
    ))
    drawRoundRect(
        color = ink.copy(alpha = 0.10f),
        topLeft = Offset(left, top), size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.04f),
    )
    drawRoundRect(
        color = ink.copy(alpha = 0.75f),
        topLeft = Offset(left, top), size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.04f),
        style = Stroke(width = u * 0.022f),
    )
    // Play.
    val s = u * 0.11f
    val play = Path().apply {
        moveTo(left + w / 2 - s * 0.4f, top + h / 2 - s)
        lineTo(left + w / 2 + s * 0.8f, top + h / 2)
        lineTo(left + w / 2 - s * 0.4f, top + h / 2 + s)
        close()
    }
    drawPath(play, ink.copy(alpha = 0.9f))
}

/** A record, turning. */
private fun DrawScope.drawRecord(phase: Float) {
    val u = size.minDimension
    val ink = Color(0xFFD9A8FF)
    val c = Offset(size.width * 0.5f, size.height * 0.45f)
    val r = u * 0.28f
    drawCircle(Color.Black.copy(alpha = 0.45f), radius = r, center = c)
    drawCircle(ink.copy(alpha = 0.55f), radius = r, center = c, style = Stroke(width = u * 0.02f))
    // Grooves.
    for (i in 1..3) {
        drawCircle(ink.copy(alpha = 0.18f), radius = r * (0.78f - i * 0.16f), center = c, style = Stroke(width = u * 0.012f))
    }
    drawCircle(ink.copy(alpha = 0.9f), radius = u * 0.035f, center = c)
    // A highlight that sweeps round, so it reads as turning rather than stuck.
    val angle = phase * 6.2831853f
    drawCircle(
        ink.copy(alpha = 0.85f), radius = u * 0.018f,
        center = Offset(c.x + cos(angle) * r * 0.82f, c.y + sin(angle) * r * 0.82f),
    )
    // Level, keeping time underneath.
    val bars = 7
    val bw = u * 0.035f
    val gap = u * 0.028f
    val total = bars * bw + (bars - 1) * gap
    val baseY = size.height * 0.84f
    for (i in 0 until bars) {
        val t = (phase * 2f + i * 0.35f) % 2f
        val amp = if (t > 1f) 2f - t else t
        val bh = u * (0.05f + amp * 0.16f)
        drawRoundRect(
            color = ink.copy(alpha = 0.5f),
            topLeft = Offset(c.x - total / 2 + i * (bw + gap), baseY - bh),
            size = Size(bw, bh),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2),
        )
    }
}

/** A pan on the heat, with steam coming off it. */
private fun DrawScope.drawPan(phase: Float) {
    val u = size.minDimension
    val ink = Color(0xFFFFC08A)
    val cx = size.width * 0.5f
    val cy = size.height * 0.62f
    val rw = u * 0.30f
    // Steam first: it belongs behind the pan.
    for (i in 0..2) {
        val x = cx + (i - 1) * u * 0.16f
        val lift = u * (0.10f + phase * 0.10f) * (1f + i * 0.15f)
        val steam = Path().apply {
            moveTo(x, cy - u * 0.16f)
            cubicTo(
                x - u * 0.08f, cy - u * 0.16f - lift * 0.6f,
                x + u * 0.08f, cy - u * 0.16f - lift * 1.1f,
                x, cy - u * 0.16f - lift * 1.7f,
            )
        }
        drawPath(steam, ink.copy(alpha = 0.30f - i * 0.06f), style = Stroke(width = u * 0.022f))
    }
    // The pan.
    drawArc(
        color = ink.copy(alpha = 0.85f), startAngle = 0f, sweepAngle = 180f, useCenter = true,
        topLeft = Offset(cx - rw, cy - rw * 0.45f), size = Size(rw * 2, rw * 0.9f),
    )
    drawLine(
        ink.copy(alpha = 0.85f),
        Offset(cx + rw * 0.95f, cy - rw * 0.10f), Offset(cx + rw * 1.75f, cy - rw * 0.30f),
        strokeWidth = u * 0.045f,
    )
    // Heat underneath.
    drawArc(
        color = ink.copy(alpha = 0.22f + phase * 0.12f), startAngle = 200f, sweepAngle = 140f, useCenter = false,
        topLeft = Offset(cx - rw * 0.7f, cy + u * 0.06f), size = Size(rw * 1.4f, u * 0.14f),
        style = Stroke(width = u * 0.025f),
    )
}

/** A path over hills, going somewhere. */
private fun DrawScope.drawTrail(phase: Float) {
    val u = size.minDimension
    val ink = Color(0xFF9BE6BE)
    // Hills.
    val hills = Path().apply {
        moveTo(0f, size.height)
        lineTo(0f, size.height * 0.68f)
        cubicTo(
            size.width * 0.25f, size.height * 0.50f,
            size.width * 0.45f, size.height * 0.78f,
            size.width * 0.68f, size.height * 0.62f,
        )
        cubicTo(
            size.width * 0.85f, size.height * 0.52f,
            size.width * 0.92f, size.height * 0.66f,
            size.width, size.height * 0.58f,
        )
        lineTo(size.width, size.height)
        close()
    }
    drawPath(hills, ink.copy(alpha = 0.16f))
    drawPath(
        Path().apply {
            moveTo(0f, size.height * 0.68f)
            cubicTo(
                size.width * 0.25f, size.height * 0.50f,
                size.width * 0.45f, size.height * 0.78f,
                size.width * 0.68f, size.height * 0.62f,
            )
            cubicTo(
                size.width * 0.85f, size.height * 0.52f,
                size.width * 0.92f, size.height * 0.66f,
                size.width, size.height * 0.58f,
            )
        },
        ink.copy(alpha = 0.45f), style = Stroke(width = u * 0.018f),
    )
    // The trail itself, dashed, climbing away.
    val trail = Path().apply {
        moveTo(size.width * 0.14f, size.height * 0.95f)
        cubicTo(
            size.width * 0.40f, size.height * 0.88f,
            size.width * 0.30f, size.height * 0.72f,
            size.width * 0.56f, size.height * 0.66f,
        )
        cubicTo(
            size.width * 0.76f, size.height * 0.61f,
            size.width * 0.70f, size.height * 0.50f,
            size.width * 0.88f, size.height * 0.45f,
        )
    }
    drawPath(
        trail, ink.copy(alpha = 0.75f),
        style = Stroke(
            width = u * 0.022f,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(u * 0.05f, u * 0.045f),
                phase * u * 0.095f,
            ),
        ),
    )
}

/** Two things to pick between. */
private fun DrawScope.drawChoice(phase: Float) {
    val u = size.minDimension
    val ink = Color(0xFFAFC0FF)
    val w = u * 0.30f
    val h = u * 0.42f
    val cy = size.height * 0.48f
    val lean = 8f + phase * 4f
    rotate(degrees = -lean, pivot = Offset(size.width * 0.38f, cy)) {
        drawRoundRect(
            color = ink.copy(alpha = 0.30f),
            topLeft = Offset(size.width * 0.38f - w / 2, cy - h / 2), size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.05f),
        )
    }
    rotate(degrees = lean, pivot = Offset(size.width * 0.62f, cy)) {
        drawRoundRect(
            color = ink.copy(alpha = 0.72f),
            topLeft = Offset(size.width * 0.62f - w / 2, cy - h / 2), size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.05f),
        )
        // A tick on the one being leaned towards.
        val t = Path().apply {
            moveTo(size.width * 0.62f - u * 0.06f, cy)
            lineTo(size.width * 0.62f - u * 0.01f, cy + u * 0.05f)
            lineTo(size.width * 0.62f + u * 0.07f, cy - u * 0.06f)
        }
        drawPath(t, Color(0xFF0A0C1B).copy(alpha = 0.8f), style = Stroke(width = u * 0.025f))
    }
}

/** An open book. */
private fun DrawScope.drawBook(phase: Float) {
    val u = size.minDimension
    val ink = Color(0xFFA9D8FF)
    val cx = size.width * 0.5f
    val cy = size.height * 0.52f
    val w = u * 0.30f
    val lift = u * (0.02f + phase * 0.02f)
    for (side in listOf(-1f, 1f)) {
        val page = Path().apply {
            moveTo(cx, cy - u * 0.16f)
            cubicTo(
                cx + side * w * 0.5f, cy - u * 0.20f - lift,
                cx + side * w * 0.9f, cy - u * 0.16f,
                cx + side * w, cy - u * 0.10f,
            )
            lineTo(cx + side * w, cy + u * 0.16f)
            cubicTo(
                cx + side * w * 0.9f, cy + u * 0.10f,
                cx + side * w * 0.5f, cy + u * 0.08f,
                cx, cy + u * 0.14f,
            )
            close()
        }
        drawPath(page, ink.copy(alpha = if (side < 0) 0.45f else 0.72f))
    }
    drawLine(ink.copy(alpha = 0.85f), Offset(cx, cy - u * 0.16f), Offset(cx, cy + u * 0.14f), strokeWidth = u * 0.014f)
}

/** Something worth marking. */
private fun DrawScope.drawCelebration(phase: Float) {
    val u = size.minDimension
    val ink = Color(0xFFFFD68A)
    val cx = size.width * 0.5f
    val cy = size.height * 0.56f
    // A rising arc of light.
    drawArc(
        color = ink.copy(alpha = 0.30f), startAngle = 200f, sweepAngle = 140f, useCenter = false,
        topLeft = Offset(cx - u * 0.34f, cy - u * 0.30f), size = Size(u * 0.68f, u * 0.60f),
        style = Stroke(width = u * 0.02f),
    )
    // Confetti, drifting up and settling.
    val seeds = listOf(
        Triple(-0.30f, -0.10f, 18f), Triple(-0.12f, -0.26f, -32f), Triple(0.06f, -0.16f, 44f),
        Triple(0.24f, -0.28f, -12f), Triple(0.34f, -0.04f, 28f), Triple(-0.22f, 0.12f, -48f),
        Triple(0.16f, 0.14f, 16f),
    )
    for ((i, seed) in seeds.withIndex()) {
        val (dx, dy, rot) = seed
        val bob = sin((phase * 6.2831853f) + i) * u * 0.02f
        val x = cx + dx * u
        val y = cy + dy * u + bob
        rotate(degrees = rot + phase * 20f, pivot = Offset(x, y)) {
            drawRoundRect(
                color = ink.copy(alpha = 0.45f + (i % 3) * 0.15f),
                topLeft = Offset(x - u * 0.022f, y - u * 0.012f),
                size = Size(u * 0.044f, u * 0.024f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.008f),
            )
        }
    }
}

/** A photograph somebody kept. */
private fun DrawScope.drawPhoto(phase: Float) {
    val u = size.minDimension
    val ink = Color(0xFFFFB3B8)
    val w = u * 0.44f
    val h = u * 0.50f
    val cx = size.width * 0.5f
    val cy = size.height * 0.48f
    // The one behind, older.
    rotate(degrees = -9f, pivot = Offset(cx, cy)) {
        drawRoundRect(
            color = ink.copy(alpha = 0.22f),
            topLeft = Offset(cx - w / 2, cy - h / 2), size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.02f),
        )
    }
    rotate(degrees = 5f - phase * 2f, pivot = Offset(cx, cy)) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.88f),
            topLeft = Offset(cx - w / 2, cy - h / 2), size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.02f),
        )
        // The picture inside it: two people, because that is what gets kept.
        val inset = u * 0.045f
        val picH = h - inset * 2 - u * 0.09f
        drawRoundRect(
            color = ink.copy(alpha = 0.55f),
            topLeft = Offset(cx - w / 2 + inset, cy - h / 2 + inset), size = Size(w - inset * 2, picH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.012f),
        )
        val py = cy - h / 2 + inset + picH * 0.62f
        drawCircle(Color.White.copy(alpha = 0.75f), radius = u * 0.035f, center = Offset(cx - u * 0.06f, py - u * 0.05f))
        drawCircle(Color.White.copy(alpha = 0.6f), radius = u * 0.028f, center = Offset(cx + u * 0.06f, py - u * 0.03f))
        drawArc(
            color = Color.White.copy(alpha = 0.7f), startAngle = 180f, sweepAngle = 180f, useCenter = true,
            topLeft = Offset(cx - u * 0.13f, py), size = Size(u * 0.26f, u * 0.16f),
        )
    }
}

/** A quiet night; the least busy picture Viro draws. */
private fun DrawScope.drawNight(phase: Float) {
    val u = size.minDimension
    val ink = Color(0xFFBBD0F0)
    val c = Offset(size.width * 0.58f, size.height * 0.40f)
    val r = u * 0.20f
    // A crescent, made by taking a bite out of a disc.
    drawCircle(ink.copy(alpha = 0.85f), radius = r, center = c)
    drawCircle(Color(0xFF060A13), radius = r * 0.88f, center = Offset(c.x - r * 0.42f, c.y - r * 0.22f))
    val stars = listOf(0.22f to 0.24f, 0.32f to 0.58f, 0.78f to 0.70f, 0.86f to 0.30f, 0.14f to 0.72f)
    for ((i, s) in stars.withIndex()) {
        val twinkle = 0.25f + 0.35f * (0.5f + 0.5f * sin(phase * 6.2831853f + i * 1.7f))
        drawCircle(
            ink.copy(alpha = twinkle),
            radius = u * (0.010f + (i % 2) * 0.005f),
            center = Offset(size.width * s.first, size.height * s.second),
        )
    }
}

/** Kept for callers that want the bounds without drawing. */
internal fun artBounds(size: Size): Rect = Rect(Offset.Zero, size)
