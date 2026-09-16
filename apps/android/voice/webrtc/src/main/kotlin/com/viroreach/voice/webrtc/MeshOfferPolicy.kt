package com.viroreach.voice.webrtc

/**
 * Deterministic glare-free offerer election for mesh conferences:
 * the lexicographically smaller device id creates the WebRTC offer.
 */
object MeshOfferPolicy {
    fun localCreatesOffer(localDeviceId: String, remoteDeviceId: String): Boolean =
        localDeviceId < remoteDeviceId
}
