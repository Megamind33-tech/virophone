package com.viroreach.app.consumer

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.viroreach.app.data.ViroDatabaseProvider
import com.viroreach.app.session.SessionManager
import com.viroreach.core.database.KnownContactEntity
import com.viroreach.core.network.BlockUserBody
import com.viroreach.core.network.ConnectionInviteBody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

private val Context.hiddenContactsStore by preferencesDataStore("viro_hidden_contacts")

class CachedContactsRepository(
    private val session: SessionManager,
    private val context: Context,
) : ContactsRepository {
    private val dao = ViroDatabaseProvider.get(context).knownContactDao()
    private val hiddenStore = context.applicationContext.hiddenContactsStore

    fun observeContacts(): Flow<List<ContactListItem>> =
        hiddenStore.data.combine(dao.observeAll()) { prefs, entities ->
            val hidden = prefs[KEY_HIDDEN_IDS] ?: emptySet()
            entities.map { it.toListItem() }
                .filter { it.id !in hidden && !it.isBlocked }
        }

    suspend fun cachedCount(): Int = filterHidden(dao.getAll().map { it.toListItem() }).size

    suspend fun loadCachedContacts(): List<ContactListItem> =
        filterHidden(dao.getAll().map { it.toListItem() })

    suspend fun findByPhone(phoneE164: String): ContactListItem? =
        dao.findByPhone(phoneE164)?.toListItem()?.takeUnless { isHidden(it.id) }

    suspend fun findByUserId(userId: String): ContactListItem? =
        dao.findByUserId(userId)?.toListItem()?.takeUnless { isHidden(it.id) }

    suspend fun findById(id: String): ContactListItem? =
        dao.findById(id)?.toListItem()?.takeUnless { isHidden(it.id) }

    suspend fun ensureCached(contact: ContactListItem): ContactListItem {
        val existing = dao.findById(contact.id)
            ?: contact.phoneE164?.let { dao.findByPhone(it) }
        if (existing != null) return existing.toListItem()
        val entity = contact.toEntity()
        dao.upsertAll(listOf(entity))
        return entity.toListItem()
    }

    override suspend fun loadContacts(): List<ContactListItem> {
        val existingMap = dao.getAll().associateBy { it.id }
        val local = DeviceContactsReader.read(context)
        if (local.isEmpty()) {
            return filterHidden(existingMap.values.map { it.toListItem() })
        }
        val phones = local.mapNotNull { it.phoneE164 }
        val enriched = try {
            val matches = session.api.discoverContacts(
                com.viroreach.core.network.DiscoverBody(phones, "ZM"),
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
        val merged = enriched.map { c -> mergeWithExisting(c, existingMap[c.id]) }
        val visible = filterHidden(merged.filter { !it.isBlocked })
        persist(visible, existingMap)
        return visible
    }

    /** Other phone numbers saved under the same device contact profile. */
    suspend fun relatedContacts(contact: ContactListItem, limit: Int = 8): List<ContactListItem> {
        val profileId = contact.deviceContactId ?: return emptyList()
        val all = filterHidden(dao.getAll().map { it.toListItem() })
        return all.asSequence()
            .filter { it.deviceContactId == profileId && it.id != contact.id && !it.isBlocked }
            .sortedBy { it.phoneE164.orEmpty() }
            .take(limit)
            .toList()
    }

    suspend fun updateDisplayName(contactId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        upsertPatch(contactId) { it.copy(customDisplayName = trimmed, updatedAt = System.currentTimeMillis()) }
    }

    suspend fun setCustomPhoto(contactId: String, uri: Uri?) {
        upsertPatch(contactId) {
            it.copy(
                customPhotoUri = uri?.toString(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    suspend fun setFavorite(contactId: String, favorite: Boolean) {
        upsertPatch(contactId) { it.copy(isFavorite = favorite, updatedAt = System.currentTimeMillis()) }
    }

    suspend fun setFavorites(contactIds: Set<String>, favorite: Boolean) {
        val all = dao.getAll().associateBy { it.id }
        val updates = contactIds.mapNotNull { id ->
            all[id]?.copy(isFavorite = favorite, updatedAt = System.currentTimeMillis())
        }
        if (updates.isNotEmpty()) dao.upsertAll(updates)
    }

    suspend fun blockContact(contactId: String): Result<Unit> = runCatching {
        val entity = dao.findById(contactId) ?: error("Contact not found")
        entity.userId?.let { userId ->
            session.api.blockUser(BlockUserBody(userId))
        }
        upsertPatch(contactId) { it.copy(isBlocked = true, updatedAt = System.currentTimeMillis()) }
    }

    suspend fun inviteContact(contactId: String): Result<String> = runCatching {
        val entity = dao.findById(contactId) ?: error("Contact not found")
        val userId = entity.userId ?: error("Contact is not on Viro Call yet")
        val response = session.api.inviteConnection(ConnectionInviteBody(userId))
        "Invite sent (${response.status})"
    }

    suspend fun hideContacts(contactIds: Set<String>) {
        if (contactIds.isEmpty()) return
        hiddenStore.edit { prefs ->
            val current = prefs[KEY_HIDDEN_IDS]?.toMutableSet() ?: mutableSetOf()
            current.addAll(contactIds)
            prefs[KEY_HIDDEN_IDS] = current
        }
        dao.deleteByIds(contactIds.toList())
    }

    suspend fun contactForCallLog(name: String, phoneE164: String?): ContactListItem? {
        phoneE164?.let { findByPhone(it) }?.let { return it }
        return phoneE164?.let {
            ensureCached(
                ContactListItem(
                    id = "calllog:$it",
                    displayName = name,
                    phoneE164 = it,
                ),
            )
        }
    }

    private suspend fun upsertPatch(contactId: String, patch: (KnownContactEntity) -> KnownContactEntity) {
        val existing = dao.findById(contactId)
            ?: error("Contact not found")
        dao.upsertAll(listOf(patch(existing)))
    }

    private fun mergeWithExisting(contact: ContactListItem, existing: KnownContactEntity?): ContactListItem {
        if (existing == null) return contact
        return contact.copy(
            isFavorite = existing.isFavorite,
            customDisplayName = existing.customDisplayName,
            customPhotoUri = existing.customPhotoUri,
            isBlocked = existing.isBlocked,
            deviceContactId = contact.deviceContactId ?: existing.deviceContactId,
            localPhotoUri = contact.localPhotoUri ?: existing.localPhotoUri,
        )
    }

    private suspend fun filterHidden(contacts: List<ContactListItem>): List<ContactListItem> {
        val hidden = hiddenStore.data.first()[KEY_HIDDEN_IDS] ?: emptySet()
        return contacts.filter { it.id !in hidden }
    }

    private suspend fun isHidden(id: String): Boolean =
        id in (hiddenStore.data.first()[KEY_HIDDEN_IDS] ?: emptySet())

    private suspend fun persist(contacts: List<ContactListItem>, existingMap: Map<String, KnownContactEntity>) {
        if (contacts.isEmpty()) return
        dao.upsertAll(
            contacts.map { c ->
                val prev = existingMap[c.id]
                KnownContactEntity(
                    id = c.id,
                    displayName = c.displayName,
                    phoneE164 = c.phoneE164,
                    deviceContactId = c.deviceContactId ?: prev?.deviceContactId,
                    userId = c.userId,
                    viroProfilePhotoUrl = c.viroProfilePhotoUrl,
                    localPhotoUri = c.localPhotoUri ?: prev?.localPhotoUri,
                    customDisplayName = c.customDisplayName ?: prev?.customDisplayName,
                    customPhotoUri = c.customPhotoUri ?: prev?.customPhotoUri,
                    isReachable = c.isReachable,
                    isFavorite = c.isFavorite || prev?.isFavorite == true,
                    isBlocked = c.isBlocked || prev?.isBlocked == true,
                    updatedAt = System.currentTimeMillis(),
                )
            },
        )
    }

    private fun ContactListItem.toEntity() = KnownContactEntity(
        id = id,
        displayName = displayName,
        phoneE164 = phoneE164,
        deviceContactId = deviceContactId,
        userId = userId,
        viroProfilePhotoUrl = viroProfilePhotoUrl,
        localPhotoUri = localPhotoUri,
        customDisplayName = customDisplayName,
        customPhotoUri = customPhotoUri,
        isReachable = isReachable,
        isFavorite = isFavorite,
        isBlocked = isBlocked,
        updatedAt = System.currentTimeMillis(),
    )

    private fun KnownContactEntity.toListItem() = ContactListItem(
        id = id,
        displayName = displayName,
        phoneE164 = phoneE164,
        deviceContactId = deviceContactId,
        userId = userId,
        isReachable = isReachable,
        localPhotoUri = localPhotoUri,
        viroProfilePhotoUrl = viroProfilePhotoUrl,
        customDisplayName = customDisplayName,
        customPhotoUri = customPhotoUri,
        isFavorite = isFavorite,
        isBlocked = isBlocked,
    )

    companion object {
        private val KEY_HIDDEN_IDS = stringSetPreferencesKey("hidden_contact_ids")
    }
}
