package com.viroreach.app.consumer

import com.viroreach.app.session.SessionManager
import com.viroreach.feature.contacts.PhoneNumberFormatter

/**
 * The one place that decides what a person is called on screen.
 *
 * Every consumer surface — call log, chat title, incoming call, group roster,
 * blocked list — used to end its own expression with `?: userId.take(8)`, so a
 * Viro ID fragment stood in for a saved contact's name. That fallback fired
 * constantly rather than rarely, because accounts are created with an empty
 * display name. A fragment like "a5b4413b" is unreadable, unsearchable, cannot
 * be tapped to message, and makes a contact the user saved themselves look like
 * a stranger.
 *
 * Resolution order, and the reasoning for it:
 *  1. the name in this phone's address book — what the user chose to call them,
 *     which outranks anything the platform knows
 *  2. the peer's own display name on Viro
 *  3. their phone number, formatted — always recognisable, always actionable
 *  4. a neutral label — never an identifier
 *
 * A Viro ID is an internal key. It is not a name and must not be rendered as
 * one; engineering and diagnostic screens are the only exception.
 */
object PeerNameResolver {

    const val UNKNOWN = "Unknown caller"

    suspend fun resolve(
        session: SessionManager,
        userId: String? = null,
        phoneE164: String? = null,
        serverName: String? = null,
    ): String {
        val repo = session.contactsRepository
        val cached = runCatching {
            phoneE164?.let { repo.findByPhone(it) } ?: userId?.let { repo.findByUserId(it) }
        }.getOrNull()

        return cached?.effectiveDisplayName?.takeIf { it.isNotBlank() }
            ?: serverName?.trim()?.takeIf { it.isNotEmpty() }
            ?: formatPhone(phoneE164 ?: cached?.phoneE164)
            ?: UNKNOWN
    }

    /**
     * For the rare synchronous caller. Prefer [resolve]; this exists because a
     * couple of render paths are not suspend and would otherwise be tempted
     * back into showing an id.
     */
    fun resolveBlocking(
        session: SessionManager,
        userId: String? = null,
        phoneE164: String? = null,
        serverName: String? = null,
    ): String {
        val local = userId?.let { session.contactsRepository.displayNameForUserId(it) }
        return local?.takeIf { it.isNotBlank() }
            ?: serverName?.trim()?.takeIf { it.isNotEmpty() }
            ?: formatPhone(phoneE164)
            ?: UNKNOWN
    }

    private fun formatPhone(phoneE164: String?): String? =
        phoneE164?.takeIf { it.isNotBlank() }?.let {
            runCatching { PhoneNumberFormatter.formatE164International(it) }.getOrNull() ?: it
        }
}
