package com.viroreach.app.messaging

import com.viroreach.core.network.EnvelopeDto
import com.viroreach.core.network.MsgDto
import org.junit.Assert.*
import org.junit.Test

class PendingDecryptionTest {
    private val now = 1_000_000_000L

    @Test fun `retries back off and stay restrained`() {
        assertEquals(now + 5_000, PendingDecryption.nextRetryAt(1, now))
        assertEquals(now + 15_000, PendingDecryption.nextRetryAt(2, now))
        assertEquals(now + 60_000, PendingDecryption.nextRetryAt(3, now))
        // Never faster than the last step, however many attempts.
        assertEquals(now + 60 * 60_000, PendingDecryption.nextRetryAt(50, now))
        assertTrue((1..20).all { PendingDecryption.nextRetryAt(it, now) >= now + 5_000 })
    }

    @Test fun `scheduled retries stop only after enough attempts and a full day`() {
        val day = PendingDecryption.MIN_PATIENCE_MS
        val max = PendingDecryption.MAX_SCHEDULED_ATTEMPTS
        assertFalse(PendingDecryption.givesUp(max - 1, now - day, now, alwaysRecoverable = false))
        assertFalse(PendingDecryption.givesUp(max + 5, now - 1_000, now, alwaysRecoverable = false))
        assertTrue(PendingDecryption.givesUp(max, now - day, now, alwaysRecoverable = false))
    }

    @Test fun `waiting for this phone's own registration never gives up`() {
        assertFalse(PendingDecryption.givesUp(1_000, 0L, now, alwaysRecoverable = true))
    }

    @Test fun `only this device's ciphertext is queued`() {
        val dto = message(
            envelopes = listOf(
                EnvelopeDto("mine", "c1", 3),
                EnvelopeDto("theirs", "c2", 3),
            ),
        )
        val queued = PendingDecryption.forQueue(dto, "mine")
        assertEquals(listOf("mine"), queued.envelopes!!.map { it.deviceId })
        // Everything else about the message is kept, so a retry lands it exactly as arrival would.
        assertEquals(dto.copy(envelopes = queued.envelopes), queued)
    }

    @Test fun `before registration the whole copy is kept, since this device's id is not known yet`() {
        val dto = message(envelopes = listOf(EnvelopeDto("a", "c1", 3), EnvelopeDto("b", "c2", 1)))
        assertEquals(dto, PendingDecryption.forQueue(dto, null))
    }

    private fun message(envelopes: List<EnvelopeDto>) = MsgDto(
        id = "m1", conversationId = "c", senderUserId = "alice", body = null, type = "ENCRYPTED",
        clientMsgId = null, createdAt = "2026-09-25T10:00:00Z", updatedAt = null, editedAt = null,
        deletedAt = null, expiresAt = null, deliverAt = null, replyTo = null, reactions = null, media = null,
        viewOnce = null, viewed = null, forwarded = null, starred = null, metadata = null,
        senderDeviceId = "alice-phone", envelopes = envelopes,
    )
}
