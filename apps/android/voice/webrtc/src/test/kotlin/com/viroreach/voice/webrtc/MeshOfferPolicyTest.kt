package com.viroreach.voice.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshOfferPolicyTest {
    @Test
    fun `smaller device id is the offerer`() {
        assertTrue(MeshOfferPolicy.localCreatesOffer("aaa", "bbb"))
        assertFalse(MeshOfferPolicy.localCreatesOffer("bbb", "aaa"))
    }

    @Test
    fun `equal ids do not both offer`() {
        // Strict less-than: neither side offers if ids collide (should not happen).
        assertFalse(MeshOfferPolicy.localCreatesOffer("same", "same"))
    }
}
