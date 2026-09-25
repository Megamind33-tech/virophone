package com.viroreach.app.moments

import com.viroreach.app.moments.engine.MomentMood
import com.viroreach.core.network.MomentDto
import org.junit.Assert.*
import org.junit.Test

class NowDeckTest {
    private val m = MomentDto("m", "natasha", "FREE", null, "CONNECTIONS", "Natasha", null,
        "2026-09-24T12:00:00Z", "2026-09-24T13:00:00Z", false, intent = "BE")

    @Test fun `time left reads as a person would say it`() {
        assertEquals("Ending now", remainingPhrase(0))
        assertEquals("27 min remaining", remainingPhrase(27))
        assertEquals("1 h remaining", remainingPhrase(60))
        assertEquals("1 h 10 min remaining", remainingPhrase(70))
    }

    @Test fun `mood is a phrase, never the raw value`() {
        assertEquals("Feeling a bit low", MomentMood.SAD.feeling())
        assertEquals("In good spirits", MomentMood.HAPPY.feeling())
        for (mood in MomentMood.values()) assertNotEquals(mood.key, mood.feeling())
    }

    @Test fun `the personal message never repeats the status`() {
        assertNull(m.personalMessage())
        assertNull(m.copy(invitationText = "  ").personalMessage())
        assertNull(m.copy(invitationText = "wants company").personalMessage())
        assertEquals("Just don't want to be alone right now.",
            m.copy(invitationText = " Just don't want to be alone right now. ").personalMessage())
        assertEquals("Wants company", m.activity())
    }
}
