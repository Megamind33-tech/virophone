package com.viroreach.app.moments

import com.viroreach.core.network.MomentDto
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MomentCardContentTest {
    private val m = MomentDto("m", "veronica", "WORKING", "Studying", "CONNECTIONS", "Veronica", null,
        "2026-09-24T12:00:00Z", "2026-09-24T13:00:00Z", false, intent = "STAY")

    @Test fun `study stays an activity even when its room uses quiet presence`() {
        assertEquals(MomentComposition.ACTIVITY, m.composition())
        assertEquals("Studying", m.headline())
        assertNull(m.cardContext())
        assertEquals("12 min ago", m.age(Instant.parse("2026-09-24T12:12:00Z").toEpochMilli()))
    }

    @Test fun `custom words lead and optional invitation supports them`() {
        val thought = m.copy(type = "CUSTOM", text = "Finished my project", invitationText = "Come celebrate")
        assertEquals(MomentComposition.WORDS, thought.composition())
        assertEquals("Finished my project", thought.headline())
        assertEquals("Come celebrate", thought.cardContext())
    }

    @Test fun `company uses explicit invitation and never invents a feeling`() {
        val company = m.copy(type = "FREE", text = null, intent = "BE", invitationText = "Sit with me")
        assertEquals(MomentComposition.COMPANY, company.composition())
        assertEquals("Sit with me", company.headline())
        assertNull(company.cardContext())
        assertEquals("Wants company", company.copy(invitationText = null).headline())
    }
}
