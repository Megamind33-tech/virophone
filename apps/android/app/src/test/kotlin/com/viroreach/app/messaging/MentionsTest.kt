package com.viroreach.app.messaging

import com.viroreach.app.messaging.ui.findMentions
import com.viroreach.app.messaging.ui.mentionPrefix
import com.viroreach.app.messaging.ui.mentionsIn
import com.viroreach.app.messaging.ui.replaceMentionPrefix
import com.viroreach.app.people.ViroLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionsTest {
    @Test
    fun `the word being typed after an at sign`() {
        assertNull(mentionPrefix(""))
        assertNull(mentionPrefix("no mention here"))
        assertEquals("", mentionPrefix("@"))
        assertEquals("Bo", mentionPrefix("are you there @Bo"))
        assertEquals("Mary Ja", mentionPrefix("@Mary Ja"))
        // An email address is not a mention.
        assertNull(mentionPrefix("write to me@example.com"))
        // Once they've moved on, the list closes.
        assertNull(mentionPrefix("@Mary Jane are you coming today"))
    }

    @Test
    fun `picking a name replaces what was typed`() {
        assertEquals("@Bob ", replaceMentionPrefix("@Bo", "Bob"))
        assertEquals("are you there @Bob ", replaceMentionPrefix("are you there @Bo", "Bob"))
        assertEquals("hello", replaceMentionPrefix("hello", "Bob"))
    }

    @Test
    fun `only names still in the text are sent`() {
        val named = mapOf("Bob" to "u-bob", "Carol" to "u-carol")
        assertEquals(listOf("u-bob"), mentionsIn("@Bob are you playing?", named))
        assertEquals(listOf("u-bob", "u-carol"), mentionsIn("@Bob and @Carol", named))
        // Deleted from the message before sending: not a mention any more.
        assertTrue(mentionsIn("never mind", named).isEmpty())
    }

    @Test
    fun `highlighting finds the names in a message`() {
        val spans = findMentions("@Bob and @Mary Jane are in", listOf("Bob", "Mary Jane"))
        assertEquals(2, spans.size)
        assertEquals("@Bob", "@Bob and @Mary Jane are in".substring(spans[0].start, spans[0].end))
        assertEquals("@Mary Jane", "@Bob and @Mary Jane are in".substring(spans[1].start, spans[1].end))
        // Without a member list it still highlights a single word.
        assertEquals(1, findMentions("thanks @bob").size)
        assertTrue(findMentions("mail me@example.com").isEmpty())
    }

    @Test
    fun `group links are recognised, and nothing else is`() {
        assertEquals("AbC123_-xyz", ViroLinks.groupCodeFromUrl("viro://g/AbC123_-xyz"))
        assertEquals("AbC123_-xyz", ViroLinks.groupCodeFromUrl("https://reach.viro3.online/api/v1/invite/g/AbC123_-xyz"))
        assertNull(ViroLinks.groupCodeFromUrl("viro://u/chanda.mwale"))
        assertNull(ViroLinks.groupCodeFromUrl("https://reach.viro3.online/api/v1/invite/mwila"))
        assertNull(ViroLinks.groupCodeFromUrl("viro://g/short"))
        assertNull(ViroLinks.groupCodeFromUrl("viro://g/has spaces here"))
        assertNull(ViroLinks.groupCodeFromUrl(null))
        // A personal link is never read as a group one, and vice versa.
        assertNull(ViroLinks.viroIdFromUrl("viro://g/AbC123_-xyz"))
        assertNull(ViroLinks.viroIdFromUrl("https://reach.viro3.online/api/v1/invite/g/AbC123_-xyz"))
    }
}
