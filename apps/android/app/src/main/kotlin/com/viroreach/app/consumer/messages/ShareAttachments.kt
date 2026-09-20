package com.viroreach.app.consumer.messages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.viroreach.app.messaging.ContactCard
import com.viroreach.app.messaging.MessagingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The server takes documents up to 25 MB. */
const val MAX_DOCUMENT_BYTES = 25L * 1024 * 1024

/** A picked document, copied into Viro's own storage with the name the phone gave it. */
data class PickedDocument(val file: File, val name: String, val mime: String)

/**
 * Copies the picked file into Viro's outgoing folder. The Uri from the picker
 * is only readable while this screen holds the grant, and the upload happens
 * later from the outbox — possibly after a restart.
 */
suspend fun readDocument(context: Context, repo: MessagingRepository, uri: Uri): PickedDocument? =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            var name = "document"
            resolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i)?.takeIf { it.isNotBlank() }?.let { name = it }
            }
            val extension = name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 8 } ?: "bin"
            val out = repo.media.newOutgoingFile(extension)
            resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                ?: return@runCatching null
            if (out.length() == 0L) {
                out.delete()
                return@runCatching null
            }
            PickedDocument(out, name.substringAfterLast('/').substringAfterLast('\\'), mime)
        }.getOrNull()
    }

/** Hands a downloaded document to whichever app on the phone opens that kind of file. */
fun openDocument(context: Context, file: File, mime: String?): Boolean {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return false
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime?.takeIf { it.isNotBlank() } ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/**
 * Opens the phone's "add contact" screen, prefilled. Viro never writes to the
 * address book itself — the person confirms in their own contacts app.
 */
fun saveContactToPhone(context: Context, card: ContactCard): Boolean {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.NAME, card.name)
        card.phones.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        card.phones.getOrNull(1)?.let { putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it) }
        card.viroId?.let { putExtra(ContactsContract.Intents.Insert.NOTES, "Viro: $it") }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
