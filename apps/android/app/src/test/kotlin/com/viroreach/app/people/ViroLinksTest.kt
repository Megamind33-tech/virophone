package com.viroreach.app.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViroLinksTest {
    @Test
    fun `reads the viro scheme link`() {
        assertEquals("chanda.mwale", ViroLinks.viroIdFromUrl("viro://u/chanda.mwale"))
        assertEquals("mwila", ViroLinks.viroIdFromUrl("VIRO://U/mwila"))
        assertEquals("mwila", ViroLinks.viroIdFromUrl("viro://u/@mwila"))
    }

    @Test
    fun `reads the shared invite page link`() {
        assertEquals("mwila", ViroLinks.viroIdFromUrl("https://reach.viro3.online/api/v1/invite/mwila"))
        assertEquals("mwila", ViroLinks.viroIdFromUrl("https://reach.viro3.online/api/v1/invite/mwila?from=whatsapp"))
        assertEquals("mwila", ViroLinks.viroIdFromUrl("https://reach.viro3.online/api/v1/invite/mwila#top"))
    }

    @Test
    fun `ignores anything that isn't a Viro link`() {
        assertNull(ViroLinks.viroIdFromUrl(null))
        assertNull(ViroLinks.viroIdFromUrl(""))
        assertNull(ViroLinks.viroIdFromUrl("   "))
        assertNull(ViroLinks.viroIdFromUrl("https://example.com/"))
        assertNull(ViroLinks.viroIdFromUrl("https://reach.viro3.online/api/v1/invite/"))
        assertNull(ViroLinks.viroIdFromUrl("viro://u/"))
        assertNull(ViroLinks.viroIdFromUrl("viro://chat/abc"))
        // An email is not a Viro ID: a link must never turn into a search for someone's address.
        assertNull(ViroLinks.viroIdFromUrl("viro://u/someone@example.com"))
    }
}
