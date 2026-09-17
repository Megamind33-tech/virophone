package com.viroreach.app.consumer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.viroreach.app.data.ViroDatabaseProvider
import com.viroreach.app.session.SessionManager
import com.viroreach.core.database.KnownContactEntity
import com.viroreach.core.network.BlockUserBody
import com.viroreach.core.network.ConnectionInviteBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.hiddenContactsStore by preferencesDataStore("viro_hidden_contacts")
private val E164_PATTERN = Regex("^\\+[1-9]\\d{7,14}$")

class CachedContactsRepository(
    private val session: SessionManager,
    private val context: Context,
) : ContactsRepository {
    private val dao = ViroDatabaseProvider.get(context).knownContactDao()
    private val hiddenStore = context.applicationContext.hiddenContactsStore
    private var knownDevicePhones: Set<String>? = null
    private var debounceJob: Job? = null

    /**
     * Watches the device's own contact book so a newly saved number gets
     * checked against Viro immediately — the moment it's added — instead of
     * waiting for the next full contacts-tab refresh. Only the number(s) that
     * are actually new get sent, not the whole book.
     */
    fun watchDeviceContactChanges(scope: CoroutineScope) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(1500) // contact-sync writes commonly fire several change events in a burst
                    checkForNewlyAddedContacts()
                }
            }
        }
        context.contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            observer,
        )
    }

    private suspend fun checkForNewlyAddedContacts() {
        val region = DeviceRegion.current(context)
        val rawLocal = DeviceContactsReader.read(context, region)
        val currentPhones = rawLocal.mapNotNull { it.phoneE164 }.filter { E164_PATTERN.matches(it) }.toSet()
        val previous = knownDevicePhones
        knownDevicePhones = currentPhones
        // No baseline yet (app just started) — the normal full loadContacts()
        // flow covers this case, so treating everything as "new" here would
        // just duplicate that work.
        if (previous == null) return
        val newPhones = (currentPhones - previous).toList()
        if (newPhones.isEmpty()) return
        android.util.Log.i("ViroContacts", "NEW_CONTACT_DETECTED count=${newPhones.size}")
        refreshSpecificPhones(rawLocal, newPhones, region)
    }

    private suspend fun refreshSpecificPhones(rawLocal: List<ContactListItem>, phones: List<String>, region: String) {
        try {
            val response = session.api.discoverContacts(
                com.viroreach.core.network.DiscoverBody(phones, region),
            )
            if (response.matches.isEmpty()) return
            val matchesByPhone = response.matches.associateBy { it.phoneE164 }
            val existingMap = dao.getAll().associateBy { it.id }
            val affected = rawLocal.filter { it.phoneE164 in matchesByPhone.keys }
            val updated = affected.map { contact ->
                val match = matchesByPhone[contact.phoneE164]
                mergeWithExisting(
                    contact.copy(
                        userId = match?.userId,
                        isReachable = match != null,
                        viroProfilePhotoUrl = match?.avatarUrl,
                    ),
                    existingMap[contact.id],
                )
            }
            persist(updated, existingMap)
            android.util.Log.i("ViroContacts", "NEW_CONTACT_MATCHED count=${updated.size}")
        } catch (e: Exception) {
            android.util.Log.w("ViroContacts", "NEW_CONTACT_DISCOVER_FAILED: ${e.message}", e)
        }
    }

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
        val region = DeviceRegion.current(context)
        val rawLocal = DeviceContactsReader.read(context, region)
        if (rawLocal.isEmpty()) {
            return filterHidden(existingMap.values.map { it.toListItem() })
        }
        // The same real person is routinely saved as several separate device
        // contacts (once under a Google-synced name, again from WhatsApp,
        // again from the SIM) — each produces its own row from
        // DeviceContactsReader, which previously meant the same number showed
        // up several times in the contacts/call list. Collapse those to one
        // row per phone number, and prune the losing rows so they don't keep
        // reappearing from Room's cache on future loads.
        val byPhone = rawLocal.groupBy { it.phoneE164 }
        val local = mutableListOf<ContactListItem>()
        val droppedDuplicateIds = mutableListOf<String>()
        for (group in byPhone.values) {
            if (group.size == 1) {
                local += group[0]
            } else {
                val canonical = pickCanonicalDuplicate(group)
                local += canonical
                droppedDuplicateIds += group.filter { it.id != canonical.id }.map { it.id }
            }
        }
        if (droppedDuplicateIds.isNotEmpty()) {
            dao.deleteByIds(droppedDuplicateIds)
        }
        // Deduped — the same number commonly appears under several raw-contact
        // rows when synced across Google/WhatsApp/SIM accounts, and a phone
        // with a few hundred real contacts can easily produce several thousand
        // rows here.
        //
        // Filtered to a real E.164 shape — DeviceContactsReader falls back to
        // the raw, unparsed string (USSD codes like "*114#", partial numbers,
        // stray text) whenever normalization fails, and sending even one such
        // entry poisoned its entire 200-number batch server-side, so no real
        // contact in that batch ever got checked either.
        val allPhones = local.mapNotNull { it.phoneE164 }.distinct()
        val phones = allPhones.filter { E164_PATTERN.matches(it) }
        val skipped = allPhones.size - phones.size
        knownDevicePhones = phones.toSet()
        val enriched = try {
            // The server hard-caps a single discover request at
            // CONTACT_DISCOVERY_MAX_BATCH (200) and rejects the request
            // wholesale if exceeded — previously sending everything in one
            // call meant ANY phone with >200 contacts got a 400 back and
            // *zero* matches, even for people who are genuinely on Viro.
            val matches = mutableMapOf<String, com.viroreach.core.model.ContactDiscoveryMatch>()
            var failedBatches = 0
            for (batch in phones.chunked(DISCOVERY_BATCH_SIZE)) {
                try {
                    val response = session.api.discoverContacts(
                        com.viroreach.core.network.DiscoverBody(batch, region),
                    )
                    response.matches.forEach { matches[it.phoneE164] = it }
                } catch (e: Exception) {
                    failedBatches++
                    android.util.Log.w(
                        "ViroContacts",
                        "DISCOVER_BATCH_FAILED region=$region batchSize=${batch.size}: ${e.message}",
                        e,
                    )
                }
            }
            android.util.Log.i(
                "ViroContacts",
                "DISCOVER_OK region=$region sent=${phones.size} skippedInvalid=$skipped batches=${(phones.size + DISCOVERY_BATCH_SIZE - 1) / DISCOVERY_BATCH_SIZE} failedBatches=$failedBatches matched=${matches.size}",
            )
            local.map { contact ->
                val match = contact.phoneE164?.let { matches[it] }
                contact.copy(
                    userId = match?.userId,
                    isReachable = match != null,
                    viroProfilePhotoUrl = match?.avatarUrl,
                )
            }
        } catch (e: Exception) {
            // Previously swallowed silently — a contact discovery failure (auth,
            // network, server error) looked identical to "genuinely not on Viro",
            // with nothing in logcat to tell the two apart.
            android.util.Log.w("ViroContacts", "DISCOVER_FAILED region=$region sent=${phones.size}: ${e.message}", e)
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

    /** Prefer the copy with a photo, then the more complete-looking name, for a stable pick. */
    private fun pickCanonicalDuplicate(group: List<ContactListItem>): ContactListItem =
        group.sortedWith(
            compareByDescending<ContactListItem> { it.localPhotoUri != null }
                .thenByDescending { it.displayName.trim().length }
                .thenBy { it.id },
        ).first()

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
        // Must stay <= the server's CONTACT_DISCOVERY_MAX_BATCH (200 by default).
        private const val DISCOVERY_BATCH_SIZE = 200
    }
}
