package com.viroreach.feature.calling

import com.viroreach.core.model.AuthorizedNearbyContact
import com.viroreach.core.model.CallRouteType
import com.viroreach.core.model.ContactRelationshipState
import com.viroreach.core.model.KnownContact
import com.viroreach.core.model.PresenceState
import com.viroreach.core.model.ReachabilityState
import com.viroreach.feature.discovery.AuthorizedPeerResolver

/**
 * Offline-first peer resolution using binding tags (btag).
 * Falls back to null when no local trust material matches — peer stays anonymous.
 */
class OfflinePeerResolver(
    private val offlineTrustStore: OfflineTrustStore,
    private val knownContacts: () -> List<KnownContact>,
) : AuthorizedPeerResolver {

    override suspend fun resolve(ephemeralId: String, bindingTag: String?): AuthorizedNearbyContact? {
        if (bindingTag == null) return null
        val peerUserId = offlineTrustStore.resolvePeerByBindingTag(ephemeralId, bindingTag) ?: return null
        val local = knownContacts().find { it.userId == peerUserId } ?: return null
        return AuthorizedNearbyContact(
            contact = local.copy(
                presence = PresenceState.LOCAL_NETWORK,
                reachability = ReachabilityState.REACHABLE,
                preferredTransport = CallRouteType.LAN,
            ),
            ephemeralId = ephemeralId,
            transportType = CallRouteType.LAN,
        )
    }

}
