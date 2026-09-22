package com.viroreach.app.moments.engine

import com.viroreach.app.diagnostics.Breadcrumbs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Where a Moment session is in its life. One room, one owner, one at a time. */
enum class MomentPhase { IDLE, ENTERING, ACTIVE, LEAVING, RELEASED }

/**
 * The single owner of "is a Moment room alive right now".
 *
 * Leaving a room is not instant. Disconnecting LiveKit tears down a
 * PeerConnectionFactory, an EglBase and an audio device that owns the
 * microphone, and all of that happens on native threads after the Kotlin call
 * returns. Until this existed, leaving handed that teardown to GlobalScope and
 * forgot about it, so the next room could start building its own factory and
 * its own audio capture while the previous one was still dismantling them.
 *
 * Two of those overlapping is not a Kotlin-level bug and does not produce a
 * Kotlin-level failure: the process goes away underneath the JVM with no stack
 * trace, which is exactly the report we had, and the more times you go in and
 * out the likelier it gets.
 *
 * So there is one gate. A room announces itself, hands its teardown here when
 * it goes, and nothing may start another until that teardown has actually
 * finished. [awaitReleased] is the whole point: it is what the create sheet
 * waits on before building anything.
 */
object MomentSessionGate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val ids = AtomicLong(0)

    private val _phase = MutableStateFlow(MomentPhase.IDLE)
    val phase: StateFlow<MomentPhase> = _phase

    /** The teardown still running, if any. Joined before anything new starts. */
    private var teardown: Job? = null

    /** Identifies this session in the breadcrumbs, so overlaps are visible. */
    @Volatile
    var sessionId: String = "-"
        private set

    /**
     * A room is being opened. Returns the session id for breadcrumbs.
     *
     * Only ever called once teardown of the previous one has finished; see
     * [awaitReleased], which the create path calls first.
     */
    fun entering(momentId: String): String {
        sessionId = "s${ids.incrementAndGet()}"
        _phase.value = MomentPhase.ENTERING
        Breadcrumbs.moment("room-create-requested momentSessionId=$sessionId moment=$momentId")
        return sessionId
    }

    fun active() {
        _phase.value = MomentPhase.ACTIVE
        Breadcrumbs.moment("room-connected momentSessionId=$sessionId")
    }

    /**
     * The room has gone. [release] is everything that must finish before
     * another room may exist, and it is run exactly once however many times
     * this is called — a screen can be disposed more than once in ways that
     * are hard to see, and releasing a native object twice is its own crash.
     */
    fun leaving(release: suspend () -> Unit) {
        if (_phase.value == MomentPhase.LEAVING || _phase.value == MomentPhase.RELEASED) return
        _phase.value = MomentPhase.LEAVING
        val id = sessionId
        Breadcrumbs.moment("room-exit-requested momentSessionId=$id")
        teardown = scope.launch {
            // Bounded: a teardown that hangs must not lock Moments out of the
            // app for good. Going on after the timeout is a risk, but a
            // smaller one than a person who can never open another Moment.
            val done = withTimeoutOrNull(TEARDOWN_LIMIT_MS) {
                runCatching { release() }
                    .onFailure { Breadcrumbs.moment("room-release-failed momentSessionId=$id ${it.javaClass.simpleName}") }
                true
            }
            if (done == null) Breadcrumbs.moment("room-release-timeout momentSessionId=$id")
            _phase.value = MomentPhase.RELEASED
            Breadcrumbs.moment("room-released momentSessionId=$id")
            _phase.value = MomentPhase.IDLE
        }
    }

    /**
     * Suspends until nothing is being torn down.
     *
     * Called before opening the create sheet and before building a room. On
     * the common path — nothing pending — it returns immediately.
     */
    suspend fun awaitReleased() = mutex.withLock {
        val pending = teardown ?: return@withLock
        if (pending.isCompleted) { teardown = null; return@withLock }
        Breadcrumbs.moment("waiting-for-release momentSessionId=$sessionId")
        pending.join()
        teardown = null
    }

    /** Whether a new Moment may be started right now. */
    fun idle(): Boolean = _phase.value == MomentPhase.IDLE || _phase.value == MomentPhase.RELEASED

    private const val TEARDOWN_LIMIT_MS = 5_000L
}
