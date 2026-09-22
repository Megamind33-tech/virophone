package com.viroreach.app.moments.engine

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.controllers.RiveFileController
import app.rive.runtime.kotlin.core.Loop
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
    /** The animation in the artwork that shows this face. */
    val animation: String,
) {
    HAPPY("HAPPY", "in good spirits", "Good", Color(0xFF4CD964), "Happy"),
    SAD("SAD", "a bit low", "Low", Color(0xFF7FA6D6), "Sad"),
    ANGRY("ANGRY", "wound up", "Wound up", Color(0xFFFF6B4A), "Angry"),
    CRAZY("CRAZY", "all over the place", "Wired", Color(0xFFD9A8FF), "Crazy"),
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
    // Bumping this re-runs the check, which is what "Try again" does.
    var attempt by remember { mutableStateOf(0) }
    var broken by remember { mutableStateOf<String?>(null) }
    val blocked = remember(attempt) { riveBlockedBecause(context) }
    // Which animation to play — read back from the file rather than assumed.
    // Naming something the artboard does not have is not a caught error in
    // this runtime; it is a null pointer dereference in C++ that takes the
    // whole process with it.
    val animation = remember(attempt, blocked) { if (blocked == null) moodAnimation(context) else null }
    val reason = broken ?: blocked ?: if (animation == null) NO_ANIMATION else null
    // The listener is built once and outlives recompositions, so the callback
    // it holds has to be the current one rather than the one from first frame.
    val pick by rememberUpdatedState(onPick)
    // The artwork draws its own Happy / Sad / Angry / Crazy buttons, but the
    // state machine that would notice a press cannot be loaded, so the
    // presses are noticed here instead and the matching face is played.
    var view by remember { mutableStateOf<RiveAnimationView?>(null) }

    // Long enough on screen without dying counts as proof the artwork is safe
    // on this phone, and the note that would have disabled it is cleared.
    //
    // The wait is the whole point. Clearing it the moment the view is built
    // would clear it before the dangerous part — loading the file, making the
    // surface, drawing the first frames — has had time to happen.
    LaunchedEffect(reason, attempt) {
        if (reason == null) {
            delay(SETTLED_MS)
            com.viroreach.app.diagnostics.Breadcrumbs.moment("animation-ready")
            moodArtworkSurvived(context)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .pointerInput(animation) {
                detectTapGestures { tap ->
                    val tapped = moodAt(tap.x, tap.y, size.width, size.height)
                    if (tapped != null) {
                        runCatching {
                            view?.play(tapped.animation, loop = Loop.ONESHOT)
                        }
                        pick(tapped)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (reason == null) {
            AndroidView(
                factory = { ctx ->
                    com.viroreach.app.diagnostics.Breadcrumbs.moment("animation-init")
                    runCatching {
                        RiveAnimationView(ctx).also { view = it }.apply {
                            setRiveResource(
                                R.raw.mood_interaction,
                                animationName = animation,
                                autoplay = true,
                                fit = app.rive.runtime.kotlin.core.Fit.COVER,
                            )
                            // Kept, and currently silent. notifyStateChanged
                            // only ever fires for a state machine, and this
                            // file's cannot be instantiated — so the faces
                            // animate but a tap on one reports nothing. The
                            // wiring stays because it is correct and costs
                            // nothing; it starts working the day the .riv is
                            // re-exported with a state machine that loads.
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
                    }.getOrElse { e ->
                        val why = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                        Log.w(TAG, "mood artwork would not start: $why")
                        com.viroreach.app.diagnostics.Breadcrumbs.moment("animation-failed")
                        noteMoodReason(ctx, why)
                        broken = why
                        android.view.View(ctx)
                    }
                },
                onRelease = { view ->
                    // The renderer owns a TextureView and native objects; it
                    // must be stopped and let go when the screen goes, not
                    // left for whenever the view happens to be collected.
                    com.viroreach.app.diagnostics.Breadcrumbs.moment("animation-disposed")
                    (view as? RiveAnimationView)?.let { rive ->
                        runCatching { rive.stop() }
                        runCatching { rive.reset() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Only when the artwork cannot run at all. Deliberately plain: it
            // is a way through, not a second design — but it says why it is
            // here and offers the way back, because silently looking cheaper
            // than intended is how this went unnoticed for a whole build.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                PlainMoodChoices(selected, onPick)
                Spacer(Modifier.padding(6.dp))
                Text(
                    "The animated face didn't load on this phone.",
                    color = ViroColors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    reason,
                    color = ViroColors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (attempt < MAX_RETRIES) "Try again" else "Using the plain version on this phone.",
                    color = ViroColors.BlueAccent,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable(enabled = attempt < MAX_RETRIES) {
                            // Deliberately limited. "Try again" rebuilds the
                            // very thing suspected of killing the process, so
                            // it is a couple of attempts a person asked for,
                            // never a loop. The device flag is only cleared
                            // once the artwork has actually drawn for a while
                            // (see above), not merely because it was retried.
                            com.viroreach.app.diagnostics.CrashReporter.forget(context, RIVE_KEY)
                            broken = null
                            attempt++
                        }
                        .padding(12.dp),
                )
            }
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
private fun riveBlockedBecause(context: Context): String? {
    val app = context.applicationContext
    if (com.viroreach.app.diagnostics.CrashReporter.isUnsafe(app, RIVE_KEY)) {
        val why = com.viroreach.app.diagnostics.CrashReporter.reason(app, RIVE_KEY)
            ?: "It closed the app last time it was opened."
        Log.w(TAG, "mood artwork disabled on this phone: $why")
        return why
    }
    return runCatching {
        com.viroreach.app.diagnostics.CrashReporter.attempting(app, RIVE_KEY)
        Rive.init(app)
        null
    }.getOrElse { e ->
        val why = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        Log.w(TAG, "mood artwork unavailable on this device: $why")
        // A caught failure is not a crash, so the guard is stood down; only
        // the explanation is kept.
        com.viroreach.app.diagnostics.CrashReporter.survived(app, RIVE_KEY)
        noteMoodReason(app, why)
        why
    }
}

/**
 * The animation to play, read back from the file rather than assumed.
 *
 * This is deliberately an animation and not the state machine, and the reason
 * is worth writing down because it cost several builds to find.
 *
 * The crash was this, from the phone:
 *
 *   signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
 *   rive::StateMachineInstance::StateMachineInstance(...)+384
 *   rive::ArtboardInstance::stateMachineNamed(...)+232
 *
 * The obvious reading — that "State Machine 1" is the wrong name — is wrong.
 * The name is right: loading this exact file in Rive's own web runtime lists
 * the artboard's state machines as ["State Machine 1"]. But asking that
 * runtime to *instantiate* it fails too, with "Problem loading file; may be
 * corrupt!", while every animation in the same file — Timeline 2, Happy, Sad,
 * Angry, Crazy — loads and plays. So the state machine inside this .riv is
 * unusable, in two independent runtimes; on Android that shows up as a null
 * returned into a constructor that does not check it, and the process dies.
 *
 * Nothing here can repair the file. What it can do is never ask for the part
 * that does not work, and check the part it does ask for against what the
 * artboard actually reports. getAnimationNames works by index and cannot fail
 * the way the lookup by name does.
 */
private fun moodAnimation(context: Context): String? = runCatching {
    val bytes = context.resources.openRawResource(R.raw.mood_interaction).use { it.readBytes() }
    val file = app.rive.runtime.kotlin.core.File(bytes)
    try {
        val names = file.firstArtboard.animationNames
        Log.i(TAG, "mood artwork animations: $names")
        val chosen = names.firstOrNull()
        if (chosen == null) noteMoodReason(context, "The artwork has no animation to play.")
        chosen
    } finally {
        runCatching { file.release() }
    }
}.getOrElse { e ->
    val why = "Couldn't read the artwork: ${e.javaClass.simpleName}"
    Log.w(TAG, why)
    noteMoodReason(context, why)
    null
}

/**
 * Which of the artwork's own buttons a tap landed on, if any.
 *
 * The artboard draws the four choices itself and would normally handle the
 * press through its state machine; that state machine cannot be instantiated,
 * so the geometry is done here. The numbers are measured, not guessed: the
 * file was rendered in Rive's web runtime and the button centres came out at
 * 0.339, 0.447, 0.553 and 0.665 across the artboard, which is an even split of
 * the band below between [ROW_LEFT] and [ROW_RIGHT].
 *
 * Anything outside that band is ignored rather than rounded to the nearest
 * face — a tap on the big face above must not quietly claim a mood nobody
 * chose.
 */
private fun moodAt(x: Float, y: Float, width: Int, height: Int): MomentMood? {
    if (width <= 0 || height <= 0) return null
    // COVER: the artboard is scaled to fill and the overflow is centred, so
    // the same sum has to be done here to know where the artwork really is.
    val scale = maxOf(width / ART_W, height / ART_H)
    val drawnW = ART_W * scale
    val drawnH = ART_H * scale
    val u = (x - (width - drawnW) / 2f) / drawnW
    val v = (y - (height - drawnH) / 2f) / drawnH
    if (v < ROW_TOP || v > ROW_BOTTOM || u < ROW_LEFT || u > ROW_RIGHT) return null
    val across = (u - ROW_LEFT) / (ROW_RIGHT - ROW_LEFT)
    val order = listOf(MomentMood.HAPPY, MomentMood.SAD, MomentMood.ANGRY, MomentMood.CRAZY)
    return order[(across * order.size).toInt().coerceIn(0, order.size - 1)]
}

/** Writes down why the artwork is not being shown, for the next person to read. */
private fun noteMoodReason(context: Context, why: String) {
    com.viroreach.app.diagnostics.CrashReporter.noteReason(context.applicationContext, RIVE_KEY, why)
}

/** The screen came up and stayed up: the attempt is called good. */
private fun moodArtworkSurvived(context: Context) {
    com.viroreach.app.diagnostics.CrashReporter.survived(context.applicationContext, RIVE_KEY)
}

private const val TAG = "ViroMood"
private const val RIVE_KEY = "mood-artwork"
/** Up, drawing and still alive this long means the artwork is fine here. */
private const val SETTLED_MS = 4_000L
/** How many times a person may ask for the artwork again before it rests. */
private const val MAX_RETRIES = 2
/** Shown when the file has nothing playable in it. */
private const val NO_ANIMATION = "The artwork has no animation this app can play."
/** The artboard's own size, which its buttons are positioned against. */
private const val ART_W = 1741f
private const val ART_H = 1751f
/** The band the four drawn buttons sit in, as fractions of the artboard. */
private const val ROW_LEFT = 0.28f
private const val ROW_RIGHT = 0.72f
private const val ROW_TOP = 0.70f
private const val ROW_BOTTOM = 0.83f
