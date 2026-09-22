package com.viroreach.app.moments.engine

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import app.rive.runtime.kotlin.controllers.RiveFileController
import app.rive.runtime.kotlin.core.PlayableInstance
import app.rive.runtime.kotlin.core.Rive
import com.viroreach.app.R
import com.viroreach.core.designsystem.ViroColors
import kotlinx.coroutines.delay

/**
 * How the person who opened a Moment is, in four answers.
 *
 * A Moment says what somebody is doing and, since the invitation line, what
 * they said. Neither says how they are, and that is often what decides whether
 * to walk in: cooking while happy and cooking while flat are different rooms.
 *
 * Four, because four is what the artwork draws and four is what a person can
 * answer without thinking. Optional everywhere — a Moment that declares no
 * mood is perfectly ordinary, and nothing invents one.
 */
enum class MomentMood(
    val key: String,
    /** How it reads about somebody else: "Mosty is in good spirits". */
    val aboutThem: String,
    /** How it reads when choosing it about yourself. */
    val aboutMe: String,
    val tint: Color,
) {
    HAPPY("HAPPY", "in good spirits", "Good", Color(0xFF4CD964)),
    SAD("SAD", "a bit low", "Low", Color(0xFF7FA6D6)),
    ANGRY("ANGRY", "wound up", "Wound up", Color(0xFFFF6B4A)),
    CRAZY("CRAZY", "all over the place", "Wired", Color(0xFFD9A8FF)),
    ;

    companion object {
        fun of(key: String?): MomentMood? = values().firstOrNull { it.key == key }

        /**
         * Which mood a state machine state belongs to.
         *
         * The artwork's states are named after the moods with a word after
         * them — "Happy  bounce 4", "Angry bounce 2" — so the mood is whichever
         * name the state starts with. Matched loosely on purpose: the file can
         * be re-exported with the numbering changed without this needing to
         * know.
         */
        fun fromState(state: String): MomentMood? {
            val lower = state.lowercase()
            return values().firstOrNull { lower.contains(it.key.lowercase()) }
        }
    }
}

/**
 * A small, quiet mark of how somebody is.
 *
 * A dot and a word rather than a face: it sits on a card next to a person's
 * name, where an expression would read as a sticker stuck on them. The word
 * carries it for anyone who cannot use the colour.
 */
@Composable
fun MoodTag(mood: MomentMood, color: Color = ViroColors.textMuted, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(mood.tint))
        Spacer(Modifier.width(6.dp))
        Text(mood.aboutThem, color = color, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

/**
 * Choosing how you are: the artwork, on a screen of its own.
 *
 * The artboard is the whole control. It asks the question, draws the four
 * faces and takes the tap; nothing is drawn over it and nothing duplicates it,
 * because a second set of buttons under a picture of buttons is how a screen
 * starts looking like a prototype.
 *
 * Which face was tapped comes back through the state machine: each mood has a
 * state named after it, so the state the machine enters is the answer.
 *
 * Its own screen for a second reason. The artwork is a TextureView, and here
 * it is built once and left alone — in a scrolling list it was being attached
 * and detached as everything above it recomposed, which is where it took the
 * app down.
 */
@Composable
fun MoodScreen(
    selected: MomentMood?,
    modifier: Modifier = Modifier,
    onPick: (MomentMood?) -> Unit,
) {
    val context = LocalContext.current
    var broken by remember { mutableStateOf(false) }
    val trusted = remember { riveTrusted(context) }
    // The listener is built once and outlives recompositions, so the callback
    // it holds has to be the current one rather than the one from first frame.
    val pick by rememberUpdatedState(onPick)

    // Long enough on screen without dying counts as proof the artwork is safe
    // on this phone, and the note that would have disabled it is cleared.
    //
    // The wait is the whole point. Clearing it the moment the view is built
    // would clear it before the dangerous part — loading the file, making the
    // surface, drawing the first frames — has had time to happen.
    LaunchedEffect(trusted) {
        if (trusted) {
            delay(SETTLED_MS)
            moodArtworkSurvived(context)
        }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (trusted && !broken) {
            AndroidView(
                factory = { ctx ->
                    runCatching {
                        RiveAnimationView(ctx).apply {
                            setRiveResource(R.raw.mood_interaction, stateMachineName = STATE_MACHINE, autoplay = true)
                            registerListener(object : RiveFileController.Listener {
                                override fun notifyStateChanged(stateMachineName: String, stateName: String) {
                                    MomentMood.fromState(stateName)?.let { pick(it) }
                                }
                                override fun notifyPlay(animation: PlayableInstance) {}
                                override fun notifyPause(animation: PlayableInstance) {}
                                override fun notifyStop(animation: PlayableInstance) {}
                                override fun notifyLoop(animation: PlayableInstance) {}
                                override fun notifyAdvance(elapsed: Float) {}
                            })
                        }
                    }.getOrElse {
                        Log.w(TAG, "mood artwork would not start: ${it.javaClass.simpleName}")
                        broken = true
                        android.view.View(ctx)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Only when the artwork cannot run at all. Deliberately plain: it
            // is a way through, not a second design.
            PlainMoodChoices(selected, onPick)
        }
    }
}

/** The four in words, for a phone the artwork will not run on. */
@Composable
private fun PlainMoodChoices(selected: MomentMood?, onPick: (MomentMood?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "How are you today?",
            color = ViroColors.textPrimary,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.padding(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mood in MomentMood.values()) {
                val chosen = selected == mood
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (chosen) mood.tint.copy(alpha = 0.22f) else ViroColors.surfaceRaised)
                        .clickable { onPick(if (chosen) null else mood) }
                        .padding(vertical = 14.dp)
                        .semantics { contentDescription = mood.aboutMe },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        mood.aboutMe,
                        color = if (chosen) mood.tint else ViroColors.textMuted,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Whether the Rive runtime may be used on this phone.
 *
 * Two questions at once: can the native library load, and did it take the app
 * down last time. A native crash leaves no exception to catch, so the only
 * defence is a note written to disk before the attempt and cleared once the
 * screen has survived — a note still there means the last attempt did not, and
 * this phone stops trying.
 */
private fun riveTrusted(context: Context): Boolean {
    val app = context.applicationContext
    if (com.viroreach.app.diagnostics.CrashReporter.isUnsafe(app, RIVE_KEY)) {
        Log.w(TAG, "mood artwork disabled on this phone after a previous crash")
        return false
    }
    return runCatching {
        com.viroreach.app.diagnostics.CrashReporter.attempting(app, RIVE_KEY)
        Rive.init(app)
        true
    }.getOrElse {
        Log.w(TAG, "mood artwork unavailable on this device: ${it.javaClass.simpleName}")
        com.viroreach.app.diagnostics.CrashReporter.survived(app, RIVE_KEY)
        false
    }
}

/** The screen came up and stayed up: the attempt is called good. */
private fun moodArtworkSurvived(context: Context) {
    com.viroreach.app.diagnostics.CrashReporter.survived(context.applicationContext, RIVE_KEY)
}

private const val TAG = "ViroMood"
/** The one state machine in the mood file. */
private const val STATE_MACHINE = "State Machine 1"
private const val RIVE_KEY = "mood-artwork"
/** Up, drawing and still alive this long means the artwork is fine here. */
private const val SETTLED_MS = 4_000L
