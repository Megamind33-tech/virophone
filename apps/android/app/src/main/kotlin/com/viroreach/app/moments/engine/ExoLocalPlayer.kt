package com.viroreach.app.moments.engine

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * The shared player on this phone, backed by Media3's ExoPlayer.
 *
 * It fetches over plain HTTP ranges from Viro's own server — nothing is
 * downloaded whole, and nothing is kept once the room ends. It does not take
 * audio focus: the room's voices use the same speaker, and a player that
 * paused itself whenever someone spoke would fall out of step with the room.
 */
class ExoLocalPlayer(context: Context, private val onFailed: () -> Unit) : LocalPlayer {
    val exo: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ false,
        )
        addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onFailed()
        })
    }

    override val positionMs: Long get() = exo.currentPosition
    /** Meant to be playing — including while it waits for data. */
    override val playing: Boolean get() = exo.playWhenReady
    override val buffering: Boolean get() = exo.playbackState == Player.STATE_BUFFERING

    override fun load(url: String, video: Boolean) {
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.playWhenReady = false
        exo.prepare()
    }

    override fun play() { exo.playWhenReady = true }
    override fun pause() { exo.playWhenReady = false }
    override fun seekTo(positionMs: Long) = exo.seekTo(positionMs)
    override fun setRate(rate: Float) { exo.playbackParameters = PlaybackParameters(rate) }

    override fun clear() {
        exo.stop()
        exo.clearMediaItems()
    }

    fun release() = exo.release()
}
