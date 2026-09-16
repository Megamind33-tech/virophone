package com.viroreach.feature.calling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPeerRegistryTest {
    @Test
    fun `findAuthorizedPeer returns endpoint after authorize`() {
        val registry = LocalPeerRegistry()
        registry.upsertAnonymous("vr1_abc", "192.168.1.20", 8765, "btag123")
        assertNull(registry.findAuthorizedPeer("usr_alice"))
        registry.authorize("vr1_abc", "usr_alice")
        val peer = registry.findAuthorizedPeer("usr_alice")
        assertEquals("192.168.1.20", peer?.hostAddress)
        assertEquals(8765, peer?.signalingPort)
        assertEquals("vr1_abc", peer?.ephemeralId)
    }

    @Test
    fun `upsert preserves peerUserId on refresh`() {
        val registry = LocalPeerRegistry()
        registry.upsertAnonymous("vr1_abc", "192.168.1.20", 8765, "btag123")
        registry.authorize("vr1_abc", "usr_alice")
        registry.upsertAnonymous("vr1_abc", "192.168.1.21", 8765, "btag456")
        assertEquals("usr_alice", registry.findAuthorizedPeer("usr_alice")?.peerUserId)
        assertEquals("192.168.1.21", registry.findAuthorizedPeer("usr_alice")?.hostAddress)
    }
}
