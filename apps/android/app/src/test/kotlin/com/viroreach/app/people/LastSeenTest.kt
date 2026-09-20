package com.viroreach.app.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class LastSeenTest {
    /** Midday, so "yesterday" and "today" can't slide over a boundary mid-test. */
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 20, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `nothing is shown when it isn't shared`() {
        assertNull(lastSeenLabel(null, now))
        assertNull(lastSeenLabel(0, now))
    }

    @Test
    fun `recent times read as minutes`() {
        assertEquals("last seen just now", lastSeenLabel(now - 30_000, now))
        assertEquals("last seen 1 minute ago", lastSeenLabel(now - 60_000, now))
        assertEquals("last seen 45 minutes ago", lastSeenLabel(now - 45 * 60_000L, now))
    }

    @Test
    fun `today and yesterday are said by name`() {
        assertTrue(lastSeenLabel(now - 3 * 3_600_000L, now)!!.startsWith("last seen today at "))
        assertTrue(lastSeenLabel(now - 26 * 3_600_000L, now)!!.startsWith("last seen yesterday at "))
    }

    @Test
    fun `older times fall back to a date`() {
        val label = lastSeenLabel(now - 10L * 24 * 3_600_000, now)!!
        assertTrue(label, label.startsWith("last seen on "))
        assertTrue(label, label.contains("Sep"))
    }

    @Test
    fun `the privacy choices read plainly`() {
        assertEquals("Everyone", visibilityLabel("EVERYONE"))
        assertEquals("My contacts", visibilityLabel("CONTACTS"))
        assertEquals("Nobody", visibilityLabel("NOBODY"))
        // An unknown or missing value is treated as the default.
        assertEquals("Everyone", visibilityLabel(null))
        assertEquals("Everyone", visibilityLabel("SOMETHING_ELSE"))
    }
}
