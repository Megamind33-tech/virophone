package com.viroreach.feature.contacts

import com.viroreach.core.model.ContactDiscoveryMatch
import com.viroreach.core.network.DiscoverBody
import com.viroreach.core.network.ViroApiService
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Private contact discovery — uploads only hashed phone identifiers.
 * Contact names NEVER leave the device.
 */
class ContactDiscoveryService(
    private val api: ViroApiService,
    private val hashSalt: String
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
            val e164 = PhoneNormalizer.normalizeToE164(contact.phoneE164) ?: return@mapNotNull null
            LocalContact(contact.localName, e164)
        }

        if (normalized.isEmpty()) return emptyList()

        val batches = normalized.chunked(MAX_BATCH_SIZE)
        val allMatches = mutableListOf<ContactDiscoveryMatch>()

        for (batch in batches) {
            val hashes = batch.map { hashPhone(it.phoneE164) }
            val response = api.discoverContacts(DiscoverBody(hashes))
            allMatches.addAll(response.matches)
        }

        val hashToLocal = normalized.associateBy { hashPhone(it.phoneE164) }

        return allMatches.mapNotNull { match ->
            val local = hashToLocal[match.phoneHash]
            if (local != null) {
                ContactMatchResult(
                    localName = local.localName,
                    match = match
                )
            } else null
        }
    }

    fun hashPhone(phoneE164: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hashSalt.toByteArray(), "HmacSHA256"))
        return mac.doFinal(phoneE164.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

data class ContactMatchResult(
    val localName: String,
    val match: ContactDiscoveryMatch
)
