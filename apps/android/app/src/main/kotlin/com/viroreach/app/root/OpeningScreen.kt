package com.viroreach.app.root

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.viroreach.core.designsystem.ViroColors
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.viroreach.app.R

/**
 * What somebody sees while Viro opens.
 *
 * The logo arrives — it grows the last little way into place and settles —
 * and then the screen breathes: a soft light behind it in the logo's own blue
 * and green, and rings going out from it the way a call does. Nothing else.
 * The particles that used to hang around the mark are gone from the image
 * itself; this is the movement they were standing in for, done calmly.
 *
 * It is always Viro navy, in either appearance, and so is the window behind
 * it and the system splash before it. The opening is one surface from the
 * moment the icon is tapped: nothing flashes grey, then navy, then light.
 *
 * The same screen covers restoring a session and getting contacts ready, so
 * the arrival plays once however long opening takes; a second arrival
 * half-way through would read as something going wrong.
 */
@Composable
fun OpeningScreen(message: String) {
    val context = LocalContext.current
    // Somebody who has turned animations off gets the logo, still — not a
    // screen that moves anyway because this one thought it knew better.
    val still = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    val arrive = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!still) {
            arrive.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow),
            )
        }
    }

    val breath = rememberInfiniteTransition(label = "opening")
    val glow by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    val ring by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "ring",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(ViroColors.NavyBackground)
            .semantics { contentDescription = message },
        contentAlignment = Alignment.Center,
    ) {
        // Light and rings, drawn behind the logo and only once it has arrived,
        // so the first thing seen is the mark rather than an effect.
        Canvas(Modifier.size(OPENING_LOGO * 3)) {
            val shown = arrive.value
            val c = Offset(size.width / 2f, size.height / 2f)
            // The logo's own radius: the rings leave from its edge, so they
            // read as coming from it rather than as a halo it sits inside.
            val base = size.minDimension / 6f

            val lift = if (still) 0.5f else glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        BRAND_BLUE.copy(alpha = (0.22f + 0.12f * lift) * shown),
                        BRAND_GREEN.copy(alpha = (0.08f + 0.06f * lift) * shown),
                        Color.Transparent,
                    ),
                    center = c,
                    radius = base * (2.1f + 0.15f * lift),
                ),
                radius = base * (2.1f + 0.15f * lift),
                center = c,
            )

            if (!still) {
                // Two rings a half-beat apart, each widening and fading as it
                // goes, so there is always one leaving and never a crowd.
                for (offset in listOf(0f, 0.5f)) {
                    val t = (ring + offset) % 1f
                    drawCircle(
                        color = BRAND_BLUE.copy(alpha = (1f - t) * 0.28f * shown),
                        radius = base * (1.05f + 1.25f * t),
                        center = c,
                        style = Stroke(width = (2.2f - 1.4f * t).dp.toPx()),
                    )
                }
            }
        }

        // Its own full-size copy of the logo, never swapped for a smaller one.
        // The shared logo images are cut for 96dp, and Android picks the one
        // matching the screen density, so at this size it would be stretched
        // by nearly half again and go soft. This one is scaled down instead.
        Image(
            painter = painterResource(R.drawable.viro_logo_opening),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(OPENING_LOGO).graphicsLayer {
                val v = arrive.value
                alpha = v
                // The last eighth of the way into place: enough to feel like an
                // arrival, not so much that it reads as a zoom.
                val s = 0.88f + 0.12f * v
                scaleX = s
                scaleY = s
            },
        )
    }
}

/**
 * Larger than the logo elsewhere in the app, and close to the size Android 12
 * draws the icon on its own splash, so the handover from that splash to this
 * screen does not visibly shrink the mark.
 */
private val OPENING_LOGO = 136.dp

/** The two colours the logo is made of, lifted for the light behind it. */
private val BRAND_BLUE = Color(0xFF2E7BFF)
private val BRAND_GREEN = Color(0xFF2EDB6B)
