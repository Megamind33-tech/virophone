package com.viroreach.app.relationships

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class CommitmentDetectorTest {
    // Saturday 19 September 2026, 10:30.
    private val now = LocalDateTime.of(2026, 9, 19, 10, 30)

    @Test
    fun callTomorrow() {
        val s = CommitmentDetector.detect("I'll call you tomorrow", now)!!
        assertEquals(CommitmentDetector.Kind.CALL, s.kind)
        assertEquals("Remind me tomorrow", s.chip)
        assertEquals(LocalDateTime.of(2026, 9, 20, 9, 0), s.due)
    }

    @Test
    fun sendDocumentsFriday() {
        val s = CommitmentDetector.detect("I'll send the documents Friday.", now)!!
        assertEquals(CommitmentDetector.Kind.SEND, s.kind)
        assertEquals("send the documents", s.action)
        assertEquals("Add commitment", s.chip)
        assertEquals(LocalDateTime.of(2026, 9, 25, 9, 0), s.due)
    }

    @Test
    fun letsMeetNextTuesday() {
        val s = CommitmentDetector.detect("Let's meet next Tuesday", now)!!
        assertEquals(CommitmentDetector.Kind.MEET, s.kind)
        assertEquals("Add to plans", s.chip)
        // Said on a Saturday, "next Tuesday" is the coming one.
        assertEquals(LocalDateTime.of(2026, 9, 22, 9, 0), s.due)
    }

    @Test
    fun nextMondaySaidOnSundayMeansAWeekOut() {
        val sunday = LocalDateTime.of(2026, 9, 20, 10, 0)
        val s = CommitmentDetector.detect("I'll call you next Monday", sunday)!!
        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 0), s.due)
    }

    @Test
    fun explicitClockTime() {
        val s = CommitmentDetector.detect("I will call Natasha this evening at 7pm", now)!!
        assertEquals(LocalDateTime.of(2026, 9, 19, 19, 0), s.due)
        assertEquals("Remind me this evening at 19:00", s.chip)
    }

    @Test
    fun quotationToday() {
        val s = CommitmentDetector.detect("I'll send the quotation today", now)!!
        assertEquals("send the quotation", s.action)
        assertEquals(LocalDateTime.of(2026, 9, 19, 17, 0), s.due)
    }

    @Test
    fun ignoresQuestionsAndPlainChat() {
        assertNull(CommitmentDetector.detect("Will you call me tomorrow?", now))
        assertNull(CommitmentDetector.detect("I called you yesterday", now))
        assertNull(CommitmentDetector.detect("ok", now))
        assertNull(CommitmentDetector.detect("I'll be fine", now))
    }

    @Test
    fun relativeHours() {
        val s = CommitmentDetector.detect("I'll get back to you in 2 hours", now)
        assertNotNull(s)
        assertEquals(CommitmentDetector.Kind.FOLLOW_UP, s!!.kind)
        assertEquals(LocalDateTime.of(2026, 9, 19, 12, 30), s.due)
    }
}
