package com.viroreach.feature.contacts

import org.junit.Assert.*
import org.junit.Test

class PhoneNumberFormatterTest {
    @Test
    fun canonicalE164_isStable() {
        val e164 = PhoneNumberFormatter.canonicalE164("0961582985", "ZM")
        assertEquals("+260961582985", e164)
    }

    @Test
    fun displayFormatting_doesNotChangeCanonicalIdentity() {
        val digits = "961582985"
        val display1 = PhoneNumberFormatter.formatForDisplay(digits, "ZM")
        val display2 = PhoneNumberFormatter.formatForDisplay(digits, "ZM")
        assertEquals(display1, display2)
        assertEquals("+260961582985", PhoneNumberFormatter.canonicalE164(digits, "ZM"))
    }

    @Test
    fun partialDigits_groupConsistently() {
        assertEquals("961", PhoneNumberFormatter.groupPartialDigits("961"))
        assertEquals("961 582", PhoneNumberFormatter.groupPartialDigits("961582"))
    }

    @Test
    fun sanitizeAuthDigits_stripsFormatting() {
        assertEquals("260961582985", PhoneNumberFormatter.sanitizeAuthDigits("+260 96 158 2985"))
    }

    @Test
    fun nationalDigitsFromE164_prefillsAuthForReturningInstall() {
        assertEquals("961582985", PhoneNumberFormatter.nationalDigitsFromE164("+260961582985"))
    }
}
