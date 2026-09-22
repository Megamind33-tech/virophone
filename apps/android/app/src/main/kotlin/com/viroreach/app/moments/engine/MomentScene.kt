package com.viroreach.app.moments.engine

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * An atmosphere: what a room feels like before anything happens in it.
 *
 * Two colours that meet in a slow glow, and a scrim that keeps text readable
 * wherever it sits. Deliberately not imagery — a background is there to hold
 * the people and the thing they are doing, not to compete with either — and
 * deliberately cheap: a canvas with two gradients, no bitmaps, nothing to
 * download on a data bundle.
 */
data class MomentSceneLook(
    /** The deep colour the room sits in. */
    val base: Color,
    /** The warmth that glows up through it. */
    val glow: Color,
    /** How much the glow drifts; 0 holds it still. Quiet rooms barely move. */
    val motion: Float,
)

/** The scenes the server can name. Anything it adds later falls back to neutral. */
fun sceneLook(scene: String): MomentSceneLook = when (scene) {
    // A kitchen in the evening: warm, domestic, never orange-on-orange.
    "KITCHEN" -> MomentSceneLook(Color(0xFF1E1410), Color(0xFF8A4A22), 0.35f)
    // Dark enough that the picture is the brightest thing in the room.
    "CINEMA" -> MomentSceneLook(Color(0xFF07070C), Color(0xFF1C1E3A), 0.15f)
    // Deep and a little rich; the artwork will take over from here later.
    "LISTENING" -> MomentSceneLook(Color(0xFF120C1C), Color(0xFF4A2A5E), 0.45f)
    // Dusk. Almost no motion — silence is allowed.
    "QUIET" -> MomentSceneLook(Color(0xFF0C1220), Color(0xFF26344F), 0.08f)
    // More energy, still grown-up.
    "PLAY" -> MomentSceneLook(Color(0xFF081A1C), Color(0xFF1E5A62), 0.55f)
    // Warm and familiar; the colour of a lamp in a living room.
    "FAMILY" -> MomentSceneLook(Color(0xFF1C1214), Color(0xFF6A3A3E), 0.25f)
    // A little more expressive — the one room allowed some gold.
    "CELEBRATION" -> MomentSceneLook(Color(0xFF1A1208), Color(0xFF7A5A1E), 0.5f)
    // Open air.
    "OUTDOORS" -> MomentSceneLook(Color(0xFF0A1612), Color(0xFF2E5A44), 0.3f)
    else -> MomentSceneLook(Color(0xFF0B1220), Color(0xFF1E3350), 0.2f)
}

/**
 * Draws a scene, easing from the last one when the room changes — the room
 * transforms rather than cutting to another screen.
 */
@Composable
fun MomentScene(
    scene: String,
    modifier: Modifier = Modifier,
    /**
     * How alive the room is right now, 0..1 — music playing, a film running.
     * The scene brightens and moves a little more with it, so a room with
     * something going on in it does not look the same as an empty one.
     */
    energy: Float = 0f,
    /**
     * How the host said they are. The room leans a little towards it — enough
     * that walking in tells you something before anyone speaks, not enough to
     * stop a kitchen looking like a kitchen.
     */
    moodTint: Color? = null,
) {
    val look = sceneLook(scene)
    val lift by animateFloatAsState(energy.coerceIn(0f, 1f), tween(1200), label = "sceneEnergy")
    val base by animateColorAsState(look.base, tween(900), label = "sceneBase")
    val glow by animateColorAsState(
        // A quarter of the way, so the mood colours the light rather than
        // replacing it.
        if (moodTint == null) look.glow else blend(look.glow, moodTint, 0.25f),
        tween(900),
        label = "sceneGlow",
    )

    // Someone who has turned animations off on their phone gets a still room.
    val context = LocalContext.current
    val reducedMotion = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val drift = rememberInfiniteTransition(label = "sceneDrift")
    val phase by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "scenePhase",
    )
    val sway = if (reducedMotion) 0f else (look.motion + lift * 0.4f) * (phase - 0.5f)

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(base)
            // The glow rises from low in the room, where people and hands are.
            drawRect(
                Brush.radialGradient(
                    colors = listOf(glow.copy(alpha = 0.85f + lift * 0.15f), glow.copy(alpha = 0f)),
                    center = Offset(size.width * (0.5f + sway * 0.4f), size.height * 0.62f),
                    radius = size.maxDimension * (0.75f + lift * 0.12f),
                ),
            )
            // A second, fainter light keeps it from looking like a spotlight.
            drawRect(
                Brush.radialGradient(
                    colors = listOf(glow.copy(alpha = 0.35f), glow.copy(alpha = 0f)),
                    center = Offset(size.width * (0.15f - sway * 0.3f), size.height * 0.1f),
                    radius = size.maxDimension * 0.55f,
                ),
            )
            // Scrims top and bottom: whatever the scene, the header and the
            // controls stay readable.
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.18f to Color.Transparent,
                    0.72f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.6f),
                ),
            )
        }
    }
}

/**
 * A scene in miniature, for picking one.
 *
 * The same two colours the room uses, so what is chosen here is what arrives —
 * no separate set of thumbnails to drift out of step with the rooms.
 */
@Composable
fun SceneSwatch(scene: String, modifier: Modifier = Modifier) {
    val look = sceneLook(scene)
    Canvas(modifier) {
        drawRect(look.base)
        drawRect(
            Brush.radialGradient(
                colors = listOf(look.glow.copy(alpha = 0.9f), look.glow.copy(alpha = 0f)),
                center = Offset(size.width * 0.5f, size.height * 0.72f),
                radius = size.maxDimension * 0.8f,
            ),
        )
    }
}

/** What each scene is called, in the words somebody choosing one would use. */
val sceneNames: List<Pair<String, String>> = listOf(
    "KITCHEN" to "Kitchen",
    "CINEMA" to "Cinema",
    "LISTENING" to "Late night",
    "QUIET" to "Dusk",
    "PLAY" to "Bright",
    "FAMILY" to "Living room",
    "CELEBRATION" to "Gold",
    "OUTDOORS" to "Outdoors",
    "NEUTRAL" to "Viro",
)

/** Mixes two colours, [amount] of the way from [from] to [to]. */
private fun blend(from: Color, to: Color, amount: Float): Color = Color(
    red = from.red + (to.red - from.red) * amount,
    green = from.green + (to.green - from.green) * amount,
    blue = from.blue + (to.blue - from.blue) * amount,
    alpha = from.alpha,
)
