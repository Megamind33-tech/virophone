package com.viroreach.app.moments.engine

import com.viroreach.app.moments.MomentRoomState
import com.viroreach.core.network.MomentPlaybackBody
import com.viroreach.core.network.MomentPlaybackDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/** What this phone's own player can do: ExoPlayer in the app, a fake in tests. */
interface LocalPlayer {
    val positionMs: Long
    val playing: Boolean
    val buffering: Boolean
    fun load(url: String, video: Boolean)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setRate(rate: Float)
    fun clear()
}

/** How a phone that has drifted gets back to the room's clock. */
sealed interface Correction {
    data object None : Correction
    data class Seek(val toMs: Long) : Correction
    data class Rate(val rate: Float) : Correction
}

object PlaybackMath {
    /** Beyond this, jump: nobody wants to wait seconds of fast-forward. */
    const val SEEK_BEYOND_MS = 1_500L
    /** Within this, leave it: people can't tell, and every correction is felt. */
    const val CLOSE_ENOUGH_MS = 300L
    /** Once nudging, settle back to normal speed inside this. */
    const val SETTLED_MS = 120L

    /** Where the room's film is at server time [serverNow]. */
    fun expectedAt(p: MomentPlaybackDto, serverNow: Long): Long {
        val moved = if (p.status == "PLAYING") ((serverNow - p.anchorAt).coerceAtLeast(0) * p.rate).toLong() else 0L
        val at = p.positionMs + moved
        return p.durationMs?.let { minOf(at, it) } ?: at
    }

    /**
     * What to do about the difference between where this phone is and where
     * the room is. Small drift is closed by playing 5% faster or slower —
     * inaudible in speech and music — rather than a jump.
     */
    fun correction(expectedMs: Long, actualMs: Long, currentRate: Float): Correction {
        val drift = actualMs - expectedMs
        return when {
            abs(drift) >= SEEK_BEYOND_MS -> Correction.Seek(expectedMs)
            abs(drift) >= CLOSE_ENOUGH_MS -> Correction.Rate(if (drift > 0) 0.95f else 1.05f)
            currentRate != 1f && abs(drift) <= SETTLED_MS -> Correction.Rate(1f)
            currentRate != 1f && (drift > 0) == (currentRate > 1f) -> Correction.Rate(1f) // overshot
            else -> Correction.None
        }
    }
}

/** What the room's player looks like on this phone. */
data class SharedPlayerView(
    val mediaId: String? = null,
    val title: String? = null,
    val kind: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val buffering: Boolean = false,
    val error: String? = null,
    val lastChangeBy: String? = null,
)

/**
 * This phone following the room's one shared player.
 *
 * The room's playback record is the truth. This loads what it names, plays or
 * pauses as it says, and keeps the local player within a fraction of a second
 * of where the room is. If this phone buffers, only this phone waits; nobody
 * else is paused for it, and once it has caught its breath it jumps to where
 * everyone is.
 */
class SharedPlayback(
    private val room: MomentRoomState,
    private val player: LocalPlayer,
    /** Turns the server's relative address into one the player can fetch. */
    private val resolve: (String) -> String,
) {
    val view = MutableStateFlow(SharedPlayerView())
    private val mutex = Mutex()
    private var loadedId: String? = null
    private var rate = 1f
    private var failedId: String? = null
    /** Viro is off screen: this phone stays quiet until it comes back. */
    private var held = false
    private var reloads = 0
    private var lastLoaded: String? = null

    /** Brings this phone into line with [p]. Called on every change and once a second. */
    suspend fun follow(p: MomentPlaybackDto?) = mutex.withLock {
        val mediaId = p?.mediaId
        if (p == null || p.status == "IDLE" || mediaId == null) {
            if (loadedId != null) player.clear()
            loadedId = null
            failedId = null
            setRate(1f)
            view.value = SharedPlayerView(lastChangeBy = p?.updatedBy)
            return@withLock
        }
        if (mediaId != loadedId) {
            if (failedId == mediaId) return@withLock // Said once; don't retry every second.
            val url = room.streamUrl(mediaId).getOrElse {
                failedId = mediaId
                view.value = base(p).copy(error = it.message ?: "Couldn't open this on your phone.")
                return@withLock
            }
            player.load(resolve(url), video = p.kind == "VIDEO")
            if (lastLoaded != mediaId) reloads = 0
            lastLoaded = mediaId
            loadedId = mediaId
            failedId = null
        }
        val expected = PlaybackMath.expectedAt(p, room.serverNow())
        val duration = p.durationMs
        val ended = duration != null && expected >= duration
        if (p.status == "PAUSED") {
            if (player.playing) player.pause()
            setRate(1f)
            if (abs(player.positionMs - p.positionMs) > PlaybackMath.CLOSE_ENOUGH_MS) player.seekTo(p.positionMs)
        } else if (p.status == "PLAYING") {
            if (ended || held) {
                if (player.playing) player.pause()
            } else if (!player.playing) {
                player.seekTo(expected)
                setRate(1f)
                player.play()
            } else if (!player.buffering) {
                when (val c = PlaybackMath.correction(expected, player.positionMs, rate)) {
                    is Correction.Seek -> { player.seekTo(c.toMs); setRate(1f) }
                    is Correction.Rate -> setRate(c.rate)
                    Correction.None -> Unit
                }
            }
        }
        view.value = base(p).copy(
            playing = p.status == "PLAYING" && !ended,
            positionMs = if (p.status == "PLAYING") expected else p.positionMs,
            buffering = player.buffering,
        )
    }

    private fun base(p: MomentPlaybackDto) = SharedPlayerView(
        mediaId = p.mediaId, title = p.title, kind = p.kind, durationMs = p.durationMs, lastChangeBy = p.updatedBy,
    )

    private fun setRate(r: Float) {
        if (r != rate) { rate = r; player.setRate(r) }
    }

    /** Once a second: keep in step, and reflect the clock in the view. */
    suspend fun tick() = follow(room.playback.value)

    // ------------------------------------------------ this person's buttons

    suspend fun play(): Result<Unit> = send(MomentPlaybackBody("PLAY", positionMs = here()))
    /** Paused where this person sees it, so that is where everyone stops. */
    suspend fun pause(): Result<Unit> = send(MomentPlaybackBody("PAUSE", positionMs = here()))
    suspend fun seek(toMs: Long): Result<Unit> = send(MomentPlaybackBody("SEEK", positionMs = toMs.coerceAtLeast(0)))
    suspend fun load(mediaId: String): Result<Unit> = send(MomentPlaybackBody("LOAD", mediaId = mediaId))
    suspend fun stop(): Result<Unit> = send(MomentPlaybackBody("STOP"))

    private fun here(): Long? = if (loadedId != null) player.positionMs else null

    private suspend fun send(body: MomentPlaybackBody): Result<Unit> {
        val result = room.playback(body)
        follow(room.playback.value)
        return result
    }

    /** Viro left the screen: stop this phone's sound without stopping the room. */
    fun pauseHere() {
        held = true
        if (player.playing) player.pause()
    }

    /** Back on screen: the next step jumps to wherever the room has got to. */
    fun resumeHere() {
        held = false
    }

    /**
     * The player could not go on — most often its address expired mid-film.
     * A fresh address is fetched and it picks up where the room is; twice at
     * most, so a file this phone genuinely can't play says so instead.
     */
    fun playerFailed() {
        val id = loadedId ?: return
        loadedId = null
        if (reloads < 2) {
            reloads++
        } else {
            failedId = id
            player.clear()
            view.value = view.value.copy(playing = false, error = "This can't be played on your phone.")
        }
    }

    fun release() {
        player.clear()
        loadedId = null
    }
}
