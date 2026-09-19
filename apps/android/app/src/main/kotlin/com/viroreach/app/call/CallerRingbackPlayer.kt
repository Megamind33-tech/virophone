package com.viroreach.app.call

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Plays the standard supervisory ringback tone (the "brr... brr..." a caller
 * hears) while waiting for the other side to answer — previously the caller
 * heard nothing at all and had no audible sign the call was even going
 * through until it either connected or failed.
 */
class CallerRingbackPlayer {
    private var toneGenerator: ToneGenerator? = null

    fun start() {
        stop()
        toneGenerator = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, ToneGenerator.MAX_VOLUME)
        }.getOrNull()
        // -1 duration means the tone repeats until stopTone() is called.
        runCatching { toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE, -1) }
    }

    fun stop() {
        runCatching { toneGenerator?.stopTone() }
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }
}
