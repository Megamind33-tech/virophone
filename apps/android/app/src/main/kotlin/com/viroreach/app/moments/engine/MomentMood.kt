package com.viroreach.app.moments.engine

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.core.errors.RiveException
import com.viroreach.app.R
import com.viroreach.core.designsystem.ViroColors

/**
 * How the person who opened a Moment is, in four answers.
 *
 * A Moment says what somebody is doing and, since the invitation line, what
 * they said. Neither says how they are, and that is often what decides whether
 * to walk in: cooking while happy and cooking while flat are different rooms.
 *
 * Four because four can be answered without thinking, and because four is what
 * the artwork draws. Optional everywhere — a Moment that declares no mood is
 * perfectly ordinary, and nothing invents one.
 */
enum class MomentMood(
    val key: String,
    /** How it reads about somebody else: "Mosty is having a good one". */
    val aboutThem: String,
    /** How it reads when choosing it about yourself. */
    val aboutMe: String,
    val tint: Color,
) {
    HAPPY("HAPPY", "in good spirits", "Good", Color(0xFF4CD964)),
    SAD("SAD", "a bit low", "Low", Color(0xFF6B8CAE)),
    ANGRY("ANGRY", "wound up", "Wound up", Color(0xFFFF6B4A)),
    CRAZY("CRAZY", "all over the place", "All over the place", Color(0xFFD9A8FF)),
    ;

    companion object {
        fun of(key: String?): MomentMood? = values().firstOrNull { it.key == key }
    }
}

/**
 * A small, quiet mark of how somebody is.
 *
 * Deliberately a dot and a word rather than a face: it sits on a card next to
 * a person's name, where a cartoon expression would read as a sticker on them.
 * The word carries it for anyone who cannot use the colour.
 */
@Composable
fun MoodTag(mood: MomentMood, color: Color = ViroColors.textMuted, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(mood.tint))
        Spacer(Modifier.width(6.dp))
        Text(
            mood.aboutThem,
            color = color,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }
}

/**
 * Choosing how you are, with the artwork answering back.
 *
 * The buttons are ours and the animation is Rive's, on purpose. If the
 * artboard fails to load, has different inputs than we expect, or the runtime
 * cannot start on a given phone, the four buttons still work and the Moment is
 * still created — the animation is the expression, never the mechanism. That
 * also keeps the control accessible: real buttons with real labels, rather
 * than tap targets inside a canvas that a screen reader cannot see.
 */
@Composable
fun MoodPicker(
    selected: MomentMood?,
    modifier: Modifier = Modifier,
    onPick: (MomentMood?) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        MoodStage(selected, Modifier.fillMaxWidth().height(180.dp))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mood in MomentMood.values()) {
                val chosen = selected == mood
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (chosen) mood.tint.copy(alpha = 0.22f) else ViroColors.surfaceRaised,
                    modifier = Modifier.weight(1f)
                        // Tapping the chosen one again clears it: saying nothing
                        // has to stay reachable once something has been said.
                        .clickable { onPick(if (chosen) null else mood) }
                        .semantics { contentDescription = mood.aboutMe },
                ) {
                    Text(
                        mood.aboutMe,
                        color = if (chosen) mood.tint else ViroColors.textMuted,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * The artwork, told which mood to wear.
 *
 * The file carries one state machine with an input named after each mood, so
 * picking one fires the input of that name. Firing is wrapped: a Rive file can
 * be re-exported with different inputs, and a mood that no longer matches an
 * input should leave the picture where it is rather than take the screen down
 * with it.
 */
@Composable
private fun MoodStage(selected: MomentMood?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Both halves have to work before the artwork is used at all: the native
    // library has to load, and a view has to be constructible. Constructing it
    // is the part that reaches hardware — RiveAnimationView is a TextureView —
    // and a factory that throws takes the whole screen down with it, which is
    // not an acceptable way for choosing a mood to fail.
    com.viroreach.app.diagnostics.Breadcrumbs.moment("mood-stage")
    var broken by remember { mutableStateOf(false) }
    val usable = RIVE_STAGE && remember { riveAvailable(context) } && !broken
    if (usable) {
        AndroidView(
            factory = { ctx ->
                runCatching {
                    RiveAnimationView(ctx).apply {
                        setRiveResource(R.raw.mood_interaction, stateMachineName = STATE_MACHINE, autoplay = true)
                    }
                }.getOrElse {
                    Log.w(TAG, "mood artwork would not start: ${it.javaClass.simpleName}")
                    broken = true
                    android.view.View(ctx)
                }
            },
            update = { view ->
                val rive = view as? RiveAnimationView
                val mood = selected
                if (rive != null && mood != null) {
                    // Named after the mood in the file: Happy, Sad, Angry, Crazy.
                    val input = mood.key.lowercase().replaceFirstChar { it.uppercase() }
                    runCatching { rive.fireState(STATE_MACHINE, input) }
                        .recoverCatching { rive.setBooleanState(STATE_MACHINE, input, true) }
                        .onFailure { e ->
                            if (e is RiveException) Log.w(TAG, "no mood input named $input")
                            else Log.w(TAG, "could not set mood: ${e.javaClass.simpleName}")
                        }
                }
            },
            modifier = modifier,
        )
    } else {
        // No artwork on this phone: the moods still read, in our own colours.
        Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (mood in MomentMood.values()) {
                val on = selected == mood
                Box(
                    Modifier.weight(1f).height(if (on) 96.dp else 64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(mood.tint.copy(alpha = if (on) 0.5f else 0.16f)),
                )
            }
        }
    }
}

/**
 * Whether the Rive runtime can run here at all.
 *
 * It ships native libraries, and a phone with an ABI they were not built for
 * throws on first touch. Asking once, here, keeps that failure away from the
 * middle of somebody opening a Moment.
 */
private fun riveAvailable(context: android.content.Context): Boolean = runCatching {
    app.rive.runtime.kotlin.core.Rive.init(context.applicationContext)
    true
}.getOrElse {
    Log.w(TAG, "mood artwork unavailable on this device: ${it.javaClass.simpleName}")
    false
}

private const val TAG = "ViroMood"
/** The one state machine in the mood file. */
private const val STATE_MACHINE = "State Machine 1"

/**
 * Whether the Rive artwork is drawn at all. Off.
 *
 * Creating a Moment was killing the app with no crash report, which means the
 * process died below the JVM — a Java exception would have been caught and
 * written down. The only native code newly on that screen is Rive, and
 * RiveAnimationView is a TextureView being attached and detached inside a lazy
 * list as the choices above it recompose, which is exactly where a renderer
 * gets torn down underneath itself.
 *
 * The mood itself is unaffected: the four buttons were always the mechanism
 * and the artwork only ever the expression, so choosing how you are still
 * works and still travels to the card and the room. This is one constant away
 * from being on again once it can be watched on a device with a log attached.
 */
private const val RIVE_STAGE = false
