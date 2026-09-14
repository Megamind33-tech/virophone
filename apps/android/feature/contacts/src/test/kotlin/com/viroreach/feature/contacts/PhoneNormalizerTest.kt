package com.viroreach.feature.contacts

import org.junit.Assert.*
import org.junit.Test

class PhoneNormalizerTest {
    @Test
    fun `normalizes Zambian local format`() {
        assertEquals("+260961582985", PhoneNormalizer.normalizeToE164("0961582985"))
    }

    @Test
    fun `preserves E164 format`() {
        assertEquals("+260961582985", PhoneNormalizer.normalizeToE164("+260961582985"))
    }

    @Test
    fun `rejects invalid numbers`() {
        assertNull(PhoneNormalizer.normalizeToE164("123"))
        assertNull(PhoneNormalizer.normalizeToE164(""))
    }
}
