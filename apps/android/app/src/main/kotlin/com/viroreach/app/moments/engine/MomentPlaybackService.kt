package com.viroreach.app.moments.engine

import android.content.Context
import android.content.Intent
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
        val player = MomentPlayerHolder.player ?: return
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Null when the room has already gone: there is nothing to control, and
        // handing back a session for a released player would crash the caller.
        if (MomentPlayerHolder.player == null) return null
        if (session == null) {
            MomentPlayerHolder.player?.let { session = MediaSession.Builder(this, it).build() }
        }
        return session
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

    /** The room is open: publish its player and start showing controls. */
    fun attach(context: Context, player: Player) {
        this.player = player
        runCatching {
            val app = context.applicationContext
            app.startService(Intent(app, MomentPlaybackService::class.java))
        }
    }

    /** The room has gone. */
    fun detach(context: Context) {
        player = null
        runCatching {
            val app = context.applicationContext
            app.stopService(Intent(app, MomentPlaybackService::class.java))
        }
    }
}
