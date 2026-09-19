package com.viroreach.app.messaging

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Records a voice note small enough for Zambian data prices: mono speech at
 * ~24 kbps (Opus where the phone supports it, AAC otherwise) — about 180 KB a
 * minute. The loudness is sampled while recording to draw the waveform, so no
 * second pass over the file is needed.
 */
class VoiceRecorder(private val context: Context, private val media: MediaFiles) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var sampler: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val levels = mutableListOf<Int>()

    private val _level = MutableStateFlow(0f)
    /** 0..1, for the live meter while recording. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    val isRecording: Boolean get() = recorder != null

    data class Recording(val file: File, val mime: String, val durationMs: Long, val waveform: String)

    fun start(): Boolean {
        stopQuietly()
        val opus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val out = media.newOutgoingFile(if (opus) "ogg" else "m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            if (opus) {
                r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                r.setAudioEncodingBitRate(24_000)
                r.setAudioSamplingRate(16_000)
            } else {
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(32_000)
                r.setAudioSamplingRate(16_000)
            }
            r.setAudioChannels(1)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = System.currentTimeMillis()
            levels.clear()
            sampler = scope.launch {
                while (isActive && recorder != null) {
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val norm = min(1f, amp / 18_000f)
                    _level.value = norm
                    levels.add((norm * 255).toInt())
                    _elapsedMs.value = System.currentTimeMillis() - startedAt
                    delay(80)
                }
            }
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            out.delete()
            false
        }
    }

    /** Stops and returns the note, or null if it was too short to be meant. */
    fun finish(): Recording? {
        val r = recorder ?: return null
        val f = file
        val duration = System.currentTimeMillis() - startedAt
        sampler?.cancel()
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        _level.value = 0f
        if (!ok || f == null || duration < 700) {
            f?.delete()
            return null
        }
        val opus = f.extension == "ogg"
        return Recording(f, if (opus) "audio/ogg" else "audio/mp4", duration, waveformOf(levels))
    }

    fun cancel() = stopQuietly()

    private fun stopQuietly() {
        sampler?.cancel()
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
        file?.delete()
        file = null
        _level.value = 0f
        _elapsedMs.value = 0L
    }

    companion object {
        const val BARS = 48

        /** Downsamples loudness to [BARS] bytes, base64 — small enough to send with the note. */
        fun waveformOf(samples: List<Int>): String {
            val bars = ByteArray(BARS)
            if (samples.isNotEmpty()) {
                for (i in 0 until BARS) {
                    val from = i * samples.size / BARS
                    val to = max(from + 1, (i + 1) * samples.size / BARS)
                    val peak = samples.subList(from, min(to, samples.size)).maxOrNull() ?: 0
                    bars[i] = peak.coerceIn(0, 255).toByte()
                }
            }
            return Base64.encodeToString(bars, Base64.NO_WRAP)
        }

        fun decodeWaveform(b64: String?): List<Float> {
            val bytes = b64?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            if (bytes == null || bytes.isEmpty()) return List(BARS) { 0.15f }
            val raw = bytes.map { (it.toInt() and 0xFF) / 255f }
            val peak = raw.maxOrNull()?.takeIf { it > 0f } ?: 1f
            return raw.map { (it / peak).coerceIn(0.08f, 1f) }
        }
    }
}

/**
 * One voice note plays at a time, at 1×, 1.5× or 2×. When one finishes, the
 * next unheard note in the chat starts by itself, as WhatsApp does.
 */
class VoicePlayer {
    data class State(
        val messageId: String? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val speed: Float = 1f,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Asked for the next note to play when one ends. */
    var nextAfter: ((String) -> Pair<String, File>?)? = null

    fun toggle(messageId: String, file: File) {
        val s = _state.value
        if (s.messageId == messageId && player != null) {
            if (s.playing) pause() else resume()
            return
        }
        play(messageId, file)
    }

    fun play(messageId: String, file: File) {
        release()
        val p = MediaPlayer()
        try {
            p.setDataSource(file.absolutePath)
            p.prepare()
            applySpeed(p, _state.value.speed)
            p.setOnCompletionListener {
                val finished = messageId
                release()
                _state.value = State(speed = _state.value.speed)
                nextAfter?.invoke(finished)?.let { (id, f) -> play(id, f) }
            }
            p.start()
            player = p
            _state.value = State(messageId, true, 0, p.duration.toLong(), _state.value.speed)
            ticker = scope.launch {
                while (isActive && player != null) {
                    _state.value = _state.value.copy(positionMs = runCatching { player?.currentPosition?.toLong() ?: 0 }.getOrDefault(0))
                    delay(120)
                }
            }
        } catch (e: Exception) {
            runCatching { p.release() }
        }
    }

    fun pause() {
        runCatching { player?.pause() }
        _state.value = _state.value.copy(playing = false)
    }

    fun resume() {
        runCatching { player?.start() }
        _state.value = _state.value.copy(playing = true)
    }

    fun seekTo(fraction: Float) {
        val p = player ?: return
        runCatching { p.seekTo((p.duration * fraction.coerceIn(0f, 1f)).toInt()) }
    }

    /** 1× → 1.5× → 2× → 1×. */
    fun cycleSpeed() {
        val next = when (_state.value.speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        _state.value = _state.value.copy(speed = next)
        player?.let { applySpeed(it, next) }
    }

    private fun applySpeed(p: MediaPlayer, speed: Float) {
        runCatching {
            val wasPlaying = p.isPlaying
            p.playbackParams = p.playbackParams.setSpeed(speed)
            if (!wasPlaying) p.pause()
        }
    }

    fun release() {
        ticker?.cancel()
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    fun stop() {
        release()
        _state.value = State(speed = _state.value.speed)
    }
}
