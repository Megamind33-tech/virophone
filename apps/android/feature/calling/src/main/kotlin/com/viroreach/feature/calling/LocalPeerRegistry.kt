package com.viroreach.feature.calling

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks LAN/Wi-Fi Direct peers with resolved signaling endpoints.
 */
class LocalPeerRegistry {
    private val peers = mutableMapOf<String, LocalPeerEndpoint>()
    private val _endpoints = MutableStateFlow<List<LocalPeerEndpoint>>(emptyList())
    val endpoints: StateFlow<List<LocalPeerEndpoint>> = _endpoints.asStateFlow()

    fun upsertAnonymous(ephemeralId: String, hostAddress: String, signalingPort: Int, bindingTag: String?) {
        val existing = peers[ephemeralId]
        peers[ephemeralId] = LocalPeerEndpoint(
            ephemeralId = ephemeralId,
            hostAddress = hostAddress,
            signalingPort = signalingPort,
            bindingTag = bindingTag,
            peerUserId = existing?.peerUserId,
        )
        publish()
    }

    fun authorize(ephemeralId: String, peerUserId: String) {
        val ep = peers[ephemeralId] ?: return
        peers[ephemeralId] = ep.copy(peerUserId = peerUserId)
        publish()
    }

    fun findAuthorizedPeer(peerUserId: String): LocalPeerEndpoint? =
        peers.values.firstOrNull { it.peerUserId == peerUserId }

    fun remove(ephemeralId: String) {
        peers.remove(ephemeralId)
        publish()
    }

    fun clear() {
        peers.clear()
        publish()
    }

    private fun publish() {
        _endpoints.value = peers.values.toList()
    }
}
