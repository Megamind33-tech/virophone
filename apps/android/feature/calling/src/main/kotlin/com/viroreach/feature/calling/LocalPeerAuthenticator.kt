package com.viroreach.feature.calling

import com.viroreach.core.network.TokenStore
import com.viroreach.feature.discovery.EphemeralIdGenerator
import org.json.JSONObject

/**
 * Offline-trust gate for local signaling — rejects unknown LAN peers.
 */
class LocalPeerAuthenticator(
    private val offlineTrust: OfflineTrustStore,
    private val tokenStore: TokenStore,
    private val ephemeralIdGenerator: EphemeralIdGenerator,
) {
    fun validateInbound(ephemeralId: String, bindingTag: String): String? =
        offlineTrust.resolvePeerByBindingTag(
            ephemeralId,
            bindingTag,
            tokenStore.getDeviceId(),
        )

    fun buildOutboundAuth(peerUserId: String): JSONObject? {
        val entry = offlineTrust.getMaterial().firstOrNull { it.peerUserId == peerUserId } ?: return null
        val eid = ephemeralIdGenerator.getCurrentId()
        val btag = offlineTrust.computeBindingTag(entry.trustToken, eid)
        return JSONObject()
            .put("ephemeralId", eid)
            .put("bindingTag", btag)
            .put("deviceId", tokenStore.getDeviceId() ?: "")
            .put("protocolVersion", entry.protocolVersion)
    }
}
