package com.viroreach.app.moments.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps what the room is playing alive when Viro is not on screen, and puts
 * the controls where a phone expects them.
 *
 * Music used to stop the moment the app went to the background, which is the
 * wrong behaviour for a room people are in together: putting the phone down
 * during dinner, or answering a message, silenced the evening for you while it
 * carried on for everyone else. Now the sound continues and the room stays in
 * step — and because a foreground service is the only honest way to keep audio
 * running, the notification it requires is the player controls rather than an
 * apology.
 *
 * The service holds no player of its own. [MomentPlayerHolder] owns it, the
 * room owns the holder, and this only publishes what is already playing.
 */
class MomentPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Null when the room has already gone: there is nothing to control, and
        // handing back a session for a released player would crash the caller.
        if (session == null) session = build()
        return session
    }

    /**
     * One session, with an id of its own.
     *
     * Two sessions built with the default id in the same process throw, which
     * is reachable by leaving a room and opening another before the old
     * service has finished being destroyed. Building it is also allowed to
     * fail: a media session is a convenience, and nothing about being in a
     * room should end because the notification could not be made.
     */
    private fun build(): MediaSession? {
        val player = MomentPlayerHolder.player ?: return null
        return runCatching { MediaSession.Builder(this, player).setId(SESSION_ID).build() }
            .getOrElse {
                Log.w(TAG, "no media controls this time: ${it.javaClass.simpleName}")
                null
            }
    }

    /**
     * Swiping Viro away stops the music. Someone who dismisses the app is
     * asking for it to stop, and audio that outlives the task it belongs to is
     * the thing people complain about.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        MomentPlayerHolder.player?.pause()
        stopSelf()
    }

    private companion object {
        const val TAG = "ViroPlayback"
        /** Distinct, so a second session cannot collide with one being torn down. */
        const val SESSION_ID = "viro-moment"
    }

    override fun onDestroy() {
        session?.run {
            release()
            session = null
        }
        super.onDestroy()
    }
}

/**
 * Where the room's player lives, so the service can publish it without owning
 * its lifetime.
 *
 * Process-wide and single, because a phone has one pair of speakers and a room
 * has one shared player. The room sets it when it opens and clears it when it
 * closes; nothing else writes here.
 */
object MomentPlayerHolder {
    @Volatile
    var player: Player? = null
        private set

    /** Whether the service has been asked to run, so it is not asked twice. */
    @Volatile
    private var running = false

    /**
     * The room is open: this is the player its controls would describe.
     *
     * Publishing it does NOT start the service. A mediaPlayback foreground
     * service may only be started when media is genuinely playing — from
     * Android 14 starting one otherwise throws
     * ForegroundServiceStartNotAllowedException and takes the app down, which
     * is what opening a Moment was doing. The service is started later, by
     * [playing], and only once there is something for it to be about.
     */
    fun attach(player: Player) {
        this.player = player
    }

    /**
     * Playback started or stopped.
     *
     * Starting is the only moment a mediaPlayback service is allowed to begin,
     * and stopping is when it should go away rather than sit in the shade
     * describing silence.
     */
    fun playing(context: Context, isPlaying: Boolean) {
        if (isPlaying == running) return
        running = isPlaying
        val app = context.applicationContext
        val intent = Intent(app, MomentPlaybackService::class.java)
        runCatching {
            if (isPlaying) app.startService(intent) else app.stopService(intent)
        }.onFailure {
            // Refused — a locked-down OEM build, or a state Android would not
            // allow. The room carries on; only the controls are missing.
            running = false
        }
    }

    /** The room has gone. */
    fun detach(context: Context) {
        player = null
        playing(context, false)
    }
}
