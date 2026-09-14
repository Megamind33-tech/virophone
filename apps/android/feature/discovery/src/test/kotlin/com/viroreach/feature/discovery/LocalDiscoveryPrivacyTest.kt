package com.viroreach.feature.discovery

import com.viroreach.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalDiscoveryPrivacyTest {
    private val authorizedEphemeralId = "vr-eph-known01"
    private val unknownEphemeralId = "vr-eph-unknown"

    private val resolver = object : AuthorizedPeerResolver {
        override suspend fun resolve(ephemeralId: String): AuthorizedNearbyContact? {
            if (ephemeralId != authorizedEphemeralId) return null
            return AuthorizedNearbyContact(
                contact = KnownContact(
                    userId = "usr_brian",
                    localName = "Brian",
                    phoneE164 = "+260961582985",
                    viroId = "@brian.m",
                    relationshipState = ContactRelationshipState.PHONE_CONTACT
                ),
                ephemeralId = ephemeralId,
                transportType = CallRouteType.LAN
            )
        }
    }

    @Test
    fun `unknown nearby peers are not exposed`() = runTest {
        val service = LocalNetworkDiscoveryService(EphemeralIdGenerator(), resolver)

        service.onPeerDiscovered(unknownEphemeralId, CallRouteType.LAN)
        service.onPeerDiscovered("vr-eph-unknown2", CallRouteType.LAN)
        service.onPeerDiscovered("vr-eph-unknown3", CallRouteType.LAN)

        assertEquals(3, service.getAnonymousPeerCount())
        assertEquals(0, service.authorizedMatches.value.size)
    }

    @Test
    fun `authorized contact is resolved from ephemeral ID`() = runTest {
        val service = LocalNetworkDiscoveryService(EphemeralIdGenerator(), resolver)

        service.onPeerDiscovered(authorizedEphemeralId, CallRouteType.LAN)
        service.onPeerDiscovered(unknownEphemeralId, CallRouteType.LAN)

        assertEquals(2, service.getAnonymousPeerCount())
        assertEquals(1, service.authorizedMatches.value.size)
        assertEquals("Brian", service.authorizedMatches.value[0].contact.localName)
    }

    @Test
    fun `ephemeral ID rotates`() {
        val gen = EphemeralIdGenerator(rotationIntervalMs = 0)
        val id1 = gen.getCurrentId()
        val id2 = gen.rotate()
        assertTrue(id1.startsWith("vr-eph-"))
        assertTrue(id2.startsWith("vr-eph-"))
    }
}
