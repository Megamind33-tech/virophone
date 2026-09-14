package com.viroreach.feature.contacts

import com.viroreach.core.model.ContactDiscoveryMatch
import com.viroreach.core.network.DiscoverBody
import com.viroreach.core.network.ViroApiService

/**
 * Authenticated contact discovery.
 * Client normalizes phones locally; sends E.164 over TLS to server.
 * Server hashes with server-only salt — no client-side secrets.
 * Contact names NEVER leave the device.
 */
class ContactDiscoveryService(
    private val api: ViroApiService,
    private val defaultRegion: String = "ZM"
) {
    companion object {
        const val MAX_BATCH_SIZE = 200
    }

    data class LocalContact(
        val localName: String,
        val phoneE164: String
    )

    suspend fun discover(localContacts: List<LocalContact>): List<ContactMatchResult> {
        val normalized = localContacts.mapNotNull { contact ->
            val e164 = PhoneNormalizer.normalizeToE164(contact.phoneE164, defaultRegion)
                ?: return@mapNotNull null
            LocalContact(contact.localName, e164)
        }

        if (normalized.isEmpty()) return emptyList()

        val batches = normalized.chunked(MAX_BATCH_SIZE)
        val allMatches = mutableListOf<ContactDiscoveryMatch>()

        for (batch in batches) {
            val phones = batch.map { it.phoneE164 }
            val response = api.discoverContacts(DiscoverBody(phones, defaultRegion))
            allMatches.addAll(response.matches)
        }

        val phoneToLocal = normalized.associateBy { it.phoneE164 }

        return allMatches.mapNotNull { match ->
            val local = phoneToLocal[match.phoneE164]
            if (local != null) {
                ContactMatchResult(localName = local.localName, match = match)
            } else null
        }
    }
}

data class ContactMatchResult(
    val localName: String,
    val match: ContactDiscoveryMatch
)
