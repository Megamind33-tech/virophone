package com.viroreach.app.messaging

import com.viroreach.app.messaging.ui.lastMoved
import com.viroreach.app.messaging.ui.liveRemaining
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveLocationTextTest {
    private val now = 1_700_000_000_000L
    private fun minutes(n: Long) = n * 60_000

    @Test
    fun `how long a live share has left`() {
        assertEquals("Ended", liveRemaining(now - 1, now))
        assertEquals("Ended", liveRemaining(now, now))
        assertEquals("Less than a minute left", liveRemaining(now + 30_000, now))
        assertEquals("4 min left", liveRemaining(now + minutes(4), now))
        assertEquals("59 min left", liveRemaining(now + minutes(59), now))
        assertEquals("1 h 0 min left", liveRemaining(now + minutes(60), now))
        assertEquals("1 h 12 min left", liveRemaining(now + minutes(72), now))
        assertEquals("8 h 0 min left", liveRemaining(now + minutes(480), now))
    }

    @Test
    fun `when it last moved`() {
        assertNull(lastMoved(null, now))
        assertEquals("Updated just now", lastMoved(now, now))
        assertEquals("Updated just now", lastMoved(now - 30_000, now))
        assertEquals("Updated a minute ago", lastMoved(now - minutes(1), now))
        assertEquals("Updated 5 min ago", lastMoved(now - minutes(5), now))
        assertEquals("Updated 2 h ago", lastMoved(now - minutes(121), now))
    }
}
