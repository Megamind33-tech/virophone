package com.viroreach.app.consumer

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.viroreach.feature.contacts.PhoneNormalizer

object DeviceContactsReader {
    fun read(context: Context, defaultRegion: String = "ZM"): List<ContactListItem> {
        val resolver = context.contentResolver
        val results = linkedMapOf<String, ContactListItem>()
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ) ?: return emptyList()
        cursor.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val photoIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx)?.trim().orEmpty()
                val raw = it.getString(numberIdx)?.trim().orEmpty()
                val deviceContactId = it.getString(idIdx) ?: continue
                if (name.isBlank() || raw.isBlank()) continue
                val e164 = PhoneNormalizer.normalizeToE164(raw, defaultRegion) ?: raw
                val photoUri = it.getString(photoIdx)?.let { uri -> Uri.parse(uri).toString() }
                val itemId = "$deviceContactId:$e164"
                if (results.containsKey(itemId)) continue
                results[itemId] = ContactListItem(
                    id = itemId,
                    displayName = name,
                    phoneE164 = e164,
                    deviceContactId = deviceContactId,
                    isReachable = false,
                    localPhotoUri = photoUri,
                )
            }
        }
        return results.values.toList()
    }

    fun findByDigitsIn(contacts: List<ContactListItem>, digits: String): ContactListItem? {
        val sanitized = digits.filter { it.isDigit() }
        if (sanitized.length < 3) return null
        return contacts.firstOrNull { contact ->
            val contactDigits = contact.phoneE164?.filter { it.isDigit() }.orEmpty()
            if (contactDigits.isEmpty()) return@firstOrNull false
            contactDigits.endsWith(sanitized) ||
                sanitized.endsWith(contactDigits.takeLast(sanitized.length.coerceAtMost(contactDigits.length)))
        }
    }

    @Deprecated("Loads all device contacts; use findByDigitsIn with a cached list")
    fun findByDigits(context: Context, digits: String, defaultRegion: String = "ZM"): ContactListItem? =
        findByDigitsIn(read(context, defaultRegion), digits)
}
