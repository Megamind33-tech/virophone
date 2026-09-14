package com.viroreach.feature.discovery

import com.viroreach.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local network discovery with privacy-first architecture.
 * Detected peers are anonymous until authorized resolution succeeds.
 */
class LocalNetworkDiscoveryService(
    private val ephemeralIdGenerator: EphemeralIdGenerator,
    private val authorizedResolver: AuthorizedPeerResolver
) {
    private val _anonymousPeers = MutableStateFlow<List<AnonymousPeer>>(emptyList())
    val anonymousPeerCount: StateFlow<Int> = MutableStateFlow(0)

    private val _authorizedMatches = MutableStateFlow<List<AuthorizedNearbyContact>>(emptyList())
    val authorizedMatches: StateFlow<List<AuthorizedNearbyContact>> = _authorizedMatches.asStateFlow()

    fun getAdvertisement(): LocalDiscoveryAdvertisement {
        return LocalDiscoveryAdvertisement(
            ephemeralId = ephemeralIdGenerator.getCurrentId(),
            capabilities = listOf("voice")
        )
    }

    /**
     * Called when NSD/mDNS discovers a peer. Peer is NOT shown to user yet.
     */
    suspend fun onPeerDiscovered(ephemeralId: String, transportType: CallRouteType) {
        val peer = AnonymousPeer(ephemeralId, listOf("voice"), transportType)
        val current = _anonymousPeers.value.toMutableList()
        if (current.none { it.ephemeralId == ephemeralId }) {
            current.add(peer)
            _anonymousPeers.value = current
        }

        // Attempt authorized resolution — unknown peers are silently discarded
        val resolved = authorizedResolver.resolve(ephemeralId)
        if (resolved != null) {
            val matches = _authorizedMatches.value.toMutableList()
            if (matches.none { it.ephemeralId == ephemeralId }) {
                matches.add(resolved)
                _authorizedMatches.value = matches
            }
        }
    }

    fun getAnonymousPeerCount(): Int = _anonymousPeers.value.size

    fun clear() {
        _anonymousPeers.value = emptyList()
        _authorizedMatches.value = emptyList()
    }
}

/**
 * Resolves ephemeral IDs against the user's authorized relationship set.
 */
interface AuthorizedPeerResolver {
    suspend fun resolve(ephemeralId: String): AuthorizedNearbyContact?
}
