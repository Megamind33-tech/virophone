package com.viroreach.app.messaging

import android.util.Log
import com.viroreach.app.BuildConfig
import com.viroreach.core.network.MsgDto

/**
 * How a sealed message that could not be opened yet is retried.
 *
 * The message stays in the chat and its ciphertext in the pending queue; this
 * only decides when the next attempt is due and when automatic attempts stop.
 * Events — a session set up with the sender's device, a reconnect, a sync,
 * registration finishing, the chat being opened — retry sooner regardless.
 */
internal object PendingDecryption {
    /** Gaps between scheduled attempts: quick at first, then restrained. */
    private val BACKOFF_MS = longArrayOf(
        5_000L,
        15_000L,
        60_000L,
        5 * 60_000L,
        15 * 60_000L,
        60 * 60_000L,
    )

    /** Scheduled attempts before the queue stops trying on its own. */
    const val MAX_SCHEDULED_ATTEMPTS = 8

    /** However many attempts, never give up inside the first day: sessions can take that long to arrive. */
    const val MIN_PATIENCE_MS = 24 * 60 * 60_000L

    /** When the attempt after [attempts] failures is due. */
    fun nextRetryAt(attempts: Int, now: Long): Long =
        now + BACKOFF_MS[(attempts - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

    /** Whether scheduled retries stop. Event-driven ones still happen for its sender's device. */
    fun givesUp(attempts: Int, firstSeenAt: Long, now: Long, alwaysRecoverable: Boolean): Boolean =
        !alwaysRecoverable && attempts >= MAX_SCHEDULED_ATTEMPTS && now - firstSeenAt >= MIN_PATIENCE_MS

    /**
     * The server's copy, trimmed to what this phone needs to try again: its
     * own envelope. Other devices' ciphertext is of no use here.
     */
    fun forQueue(dto: MsgDto, myDeviceId: String?): MsgDto =
        if (myDeviceId == null) dto
        else dto.copy(envelopes = dto.envelopes?.filter { it.deviceId == myDeviceId })
}

/**
 * Development-only trace of a sealed message's lifecycle.
 *
 * Identifiers and states only — never a body, a key or a ciphertext — so a
 * failure can be placed in transport, storage, session setup or cryptography
 * from a log alone. Silent in release builds.
 */
internal object CryptoTrace {
    private const val TAG = "ViroE2eeMsg"

    fun log(
        messageId: String,
        conversationId: String,
        senderDeviceId: String?,
        direction: String,
        source: String,
        state: String,
        reason: String? = null,
        attempt: Int? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val line = buildString {
            append("msg=").append(messageId)
            append(" conv=").append(conversationId)
            append(" senderDevice=").append(senderDeviceId ?: "-")
            append(" dir=").append(direction)
            append(" source=").append(source)
            append(" crypto=").append(state)
            if (reason != null) append(" reason=").append(reason)
            if (attempt != null) append(" attempt=").append(attempt)
        }
        runCatching { Log.d(TAG, line) }
    }
}
