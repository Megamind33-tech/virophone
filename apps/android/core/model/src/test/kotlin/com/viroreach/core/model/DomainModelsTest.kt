package com.viroreach.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DomainModelsTest {
    @Test
    fun `unknown contacts default to UNKNOWN state`() {
        val contact = KnownContact(
            userId = "usr_1",
            localName = "Unknown",
            phoneE164 = null,
            viroId = null,
            relationshipState = ContactRelationshipState.UNKNOWN
        )
        assertEquals(ContactRelationshipState.UNKNOWN, contact.relationshipState)
        assertEquals(PresenceState.OFFLINE, contact.presence)
    }

    @Test
    fun `local discovery advertisement contains no PII`() {
        val ad = LocalDiscoveryAdvertisement(ephemeralId = "vr-eph-d31f84a8")
        assertEquals("viro-reach", ad.protocol)
        assertEquals("vr-eph-d31f84a8", ad.ephemeralId)
        assertEquals(listOf("voice"), ad.capabilities)
    }
}
