package com.viroreach.feature.discovery

import com.viroreach.core.model.AuthorizedNearbyContact

/**
 * Tries offline trust resolution first, then server-backed resolution.
 */
class CompositePeerResolver(
    private val offline: AuthorizedPeerResolver,
    private val online: AuthorizedPeerResolver,
    private val isOnline: () -> Boolean,
) : AuthorizedPeerResolver {
    override suspend fun resolve(ephemeralId: String, bindingTag: String?): AuthorizedNearbyContact? {
        val offlineResult = offline.resolve(ephemeralId, bindingTag)
        if (offlineResult != null) return offlineResult
        if (isOnline()) {
            return online.resolve(ephemeralId, bindingTag)
        }
        return null
    }
}
