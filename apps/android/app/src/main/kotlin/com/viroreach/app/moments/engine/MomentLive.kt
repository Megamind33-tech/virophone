package com.viroreach.app.moments.engine

import com.viroreach.core.network.momentHttpStatus
import com.viroreach.core.network.ViroMomentsApi
import com.viroreach.voice.webrtc.MomentMediaTransport
import com.viroreach.voice.webrtc.PresenceLink
import com.viroreach.voice.webrtc.PresenceQuality
import com.viroreach.voice.webrtc.PresenceSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The live part of a Moment room: faces and voices.
 *
 * The room works without it — people are together in a Moment whether or not
 * any media flows — so everything here fails soft, into presence, and says
 * why in a sentence. Nothing is ever turned on here except by its person:
 * [setCamera] and [setMicrophone] are the only ways on, and they are called
 * from buttons. Everything automatic here only ever turns things *off*.
 */
class MomentLive(
    private val api: ViroMomentsApi,
    private val momentId: String,
    private val media: MomentMediaTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val state: StateFlow<PresenceSnapshot> = media.state

    /** A sentence for the room when something changed that its person didn't do. */
    val notice = MutableStateFlow<String?>(null)

    /** Set when live media can't be had at all here; the room carries on without it. */
    val unavailable = MutableStateFlow<String?>(null)

    private val mutex = Mutex()
    private val weak = WeakNetworkPolicy()
    private var stopped = false
    private var lastRetry = 0L

    /** Joins the room's media, publishing nothing. Safe to call again to retry. */
    suspend fun start(): Boolean = mutex.withLock { startLocked() }

    private suspend fun startLocked(): Boolean {
        if (stopped) return false
        if (media.state.value.link == PresenceLink.LIVE || media.state.value.link == PresenceLink.CONNECTING) return true
        return try {
            val pass = api.presence(momentId)
            media.connect(pass.url, pass.token)
            unavailable.value = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unavailable.value = when (momentHttpStatus(e)) {
                503 -> "Live video isn't available right now. You're still here together."
                403, 404 -> null // Not in the room (any more); the room itself says so.
                else -> "Couldn't connect video. You're still here together."
            }
            false
        }
    }

    /** Camera on or off, because its person pressed the button. */
    suspend fun setCamera(on: Boolean): Boolean = mutex.withLock {
        if (on && !startLocked()) return@withLock false
        val ok = media.setCamera(on)
        if (on && !ok) notice.value = "Couldn't start the camera."
        ok
    }

    suspend fun setMicrophone(on: Boolean): Boolean = mutex.withLock {
        if (on && !startLocked()) return@withLock false
        val ok = media.setMicrophone(on)
        if (on && !ok) notice.value = "Couldn't start the microphone."
        ok
    }

    fun flipCamera() = media.flipCamera()

    /**
     * Once a second. On a connection too weak to carry video, the camera goes
     * off so the voice survives — and it stays off until its person turns it
     * back on, because a camera that switches itself on is never acceptable.
     */
    suspend fun tick() = mutex.withLock {
        val now = media.state.value
        // The media link gave up (a long outage). Try again now and then; a
        // fresh link publishes nothing, so a camera that was on stays off.
        if (now.link == PresenceLink.FAILED && !stopped && clock() - lastRetry >= RETRY_MS) {
            lastRetry = clock()
            if (startLocked()) notice.value = "Back. Your camera and microphone are off until you turn them on."
            return@withLock
        }
        if (weak.observe(now.quality, now.cameraOn, clock())) {
            media.setCamera(false)
            notice.value = if (now.micOn) "Your connection is weak, so your camera is off. Your voice carries on."
                else "Your connection is weak, so your camera is off."
        }
    }

    /**
     * The room became something else. A quiet room shows no faces, so a camera
     * left running there would be filming for no one.
     */
    suspend fun onRoomShape(primary: String) = mutex.withLock {
        if (primary == "QUIET" && media.state.value.cameraOn) {
            media.setCamera(false)
            notice.value = "Camera off for the quiet."
        }
    }

    /** Viro left the screen: nothing keeps capturing behind the person's back. */
    suspend fun onBackground() = mutex.withLock {
        val now = media.state.value
        val was = listOfNotNull("camera".takeIf { now.cameraOn }, "microphone".takeIf { now.micOn })
        if (now.cameraOn) media.setCamera(false)
        if (now.micOn) media.setMicrophone(false)
        if (was.isNotEmpty()) {
            notice.value = "Your ${was.joinToString(" and ")} went off while Viro was in the background."
        }
    }

    /** Leaving the room, or the room ending, or being taken out of it. */
    suspend fun stop() = mutex.withLock {
        stopped = true
        media.disconnect()
    }

    private companion object {
        const val RETRY_MS = 15_000L
    }
}

/**
 * When a weak connection should cost the camera.
 *
 * Not on the first bad reading — connections dip — but once it has been weak
 * for [holdMs] straight. Recovery never turns the camera back on.
 */
class WeakNetworkPolicy(private val holdMs: Long = 6_000) {
    private var weakSince: Long? = null

    /** True when the camera should go off now. */
    fun observe(quality: PresenceQuality, cameraOn: Boolean, now: Long): Boolean {
        if (quality != PresenceQuality.WEAK && quality != PresenceQuality.LOST) {
            weakSince = null
            return false
        }
        val since = weakSince ?: now.also { weakSince = it }
        if (cameraOn && now - since >= holdMs) {
            weakSince = null
            return true
        }
        return false
    }
}
