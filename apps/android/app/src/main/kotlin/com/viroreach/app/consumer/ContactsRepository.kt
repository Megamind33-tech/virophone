package com.viroreach.app.consumer

import com.viroreach.app.session.SessionManager

/**
 * M2 boundary — M1 provides the contract only.
 */
interface ContactsRepository {
    suspend fun loadContacts(): List<ContactListItem>
}

data class ContactListItem(
    val id: String,
    val displayName: String,
    val phoneE164: String?,
    /** Android ContactsContract CONTACT_ID — groups multiple numbers under one person. */
    val deviceContactId: String? = null,
    val userId: String? = null,
    val isReachable: Boolean = false,
    val localPhotoUri: String? = null,
    val viroProfilePhotoUrl: String? = null,
    val viroProfilePhotoVersion: String? = null,
    val customDisplayName: String? = null,
    val customPhotoUri: String? = null,
    val isFavorite: Boolean = false,
    val isBlocked: Boolean = false,
) {
    val effectiveDisplayName: String
        get() = customDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: displayName
}

class PlaceholderContactsRepository : ContactsRepository {
    override suspend fun loadContacts(): List<ContactListItem> = emptyList()
}

class SessionContactsRepository(
    private val session: SessionManager,
    private val context: android.content.Context,
) : ContactsRepository {
    override suspend fun loadContacts(): List<ContactListItem> {
        val region = DeviceRegion.current(context)
        val local = DeviceContactsReader.read(context, region)
        if (local.isEmpty()) return emptyList()
        val phones = local.mapNotNull { it.phoneE164 }
        return try {
            val matches = session.api.discoverContacts(
                com.viroreach.core.network.DiscoverBody(phones, region),
            ).matches.associateBy { it.phoneE164 }
            local.map { contact ->
                val match = contact.phoneE164?.let { matches[it] }
                contact.copy(
                    userId = match?.userId,
                    isReachable = match != null,
                    viroProfilePhotoUrl = match?.avatarUrl,
                )
            }
        } catch (_: Exception) {
            local
        }
    }
}
