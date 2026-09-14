package com.viroreach.feature.discovery

import com.viroreach.core.model.AuthorizedNearbyContact
import com.viroreach.core.model.CallRouteType
import com.viroreach.core.model.ContactRelationshipState
import com.viroreach.core.model.KnownContact
import com.viroreach.core.model.PresenceState
import com.viroreach.core.model.ReachabilityState
import com.viroreach.core.network.ViroApiService
import retrofit2.HttpException

/**
 * Server-backed ephemeral ID resolution.
 * Unknown peers are silently discarded (returns null).
 */
class ServerAuthorizedPeerResolver(
    private val api: ViroApiService,
    private val knownContacts: () -> List<KnownContact>
) : AuthorizedPeerResolver {

    override suspend fun resolve(ephemeralId: String, bindingTag: String?): AuthorizedNearbyContact? {
        if (bindingTag != null) return null // server path ignores offline binding tags
        val authorizedIds = knownContacts().map { it.userId }
        try {
            val response = api.resolveEphemeral(
                com.viroreach.core.network.ResolveEphemeralBody(ephemeralId, authorizedIds)
            )
            if (!response.authorized || response.userId == null) return null

            val local = knownContacts().find { it.userId == response.userId }
            if (local == null) return null

            return AuthorizedNearbyContact(
                contact = local.copy(
                    presence = PresenceState.LOCAL_NETWORK,
                    reachability = ReachabilityState.REACHABLE,
                    preferredTransport = CallRouteType.LAN
                ),
                ephemeralId = ephemeralId,
                transportType = CallRouteType.LAN
            )
        } catch (_: HttpException) {
            return null
        }
    }
}
