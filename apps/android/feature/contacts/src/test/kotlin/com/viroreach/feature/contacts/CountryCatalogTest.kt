package com.viroreach.feature.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryCatalogTest {
    @Test
    fun `default region is Zambia`() {
        val zm = CountryCatalog.default()
        assertEquals("ZM", zm.iso2)
        assertEquals("+260", zm.dialCode)
    }

    @Test
    fun `fromE164 detects US and Zambia`() {
        assertEquals("US", CountryCatalog.fromE164("+12025550123").iso2)
        assertEquals("ZM", CountryCatalog.fromE164("+260961582985").iso2)
    }

    @Test
    fun `search matches name dial code and iso`() {
        assertTrue(CountryCatalog.search("zamb").any { it.iso2 == "ZM" })
        assertTrue(CountryCatalog.search("+44").any { it.iso2 == "GB" })
        assertTrue(CountryCatalog.search("ng").any { it.iso2 == "NG" })
    }

    @Test
    fun `flag emoji is two regional indicators`() {
        val flag = CountryCatalog.byIso("US").flagEmoji
        assertEquals(2, Character.codePointCount(flag, 0, flag.length))
    }
}
