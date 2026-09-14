package com.viroreach.feature.contacts

import org.junit.Assert.*
import org.junit.Test

class PhoneNormalizerTest {
    @Test
    fun `normalizes Zambia local format 0961582985`() {
        assertEquals("+260961582985", PhoneNormalizer.normalizeToE164("0961582985", "ZM"))
    }

    @Test
    fun `normalizes without leading zero`() {
        assertEquals("+260961582985", PhoneNormalizer.normalizeToE164("961582985", "ZM"))
    }

    @Test
    fun `normalizes country code without plus`() {
        assertEquals("+260961582985", PhoneNormalizer.normalizeToE164("260961582985", "ZM"))
    }

    @Test
    fun `preserves E164 format`() {
        assertEquals("+260961582985", PhoneNormalizer.normalizeToE164("+260961582985", "ZM"))
    }

    @Test
    fun `rejects invalid short numbers`() {
        assertNull(PhoneNormalizer.normalizeToE164("123", "ZM"))
        assertNull(PhoneNormalizer.normalizeToE164("", "ZM"))
    }
}
