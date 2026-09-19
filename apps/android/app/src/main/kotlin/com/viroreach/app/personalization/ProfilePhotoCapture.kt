package com.viroreach.app.personalization

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.viroreach.core.network.ImageDownscaler
import java.io.File

object ProfilePhotoCapture {
    fun createCameraUri(context: Context): Uri {
        val file = File.createTempFile("viro_profile_", ".jpg", context.cacheDir)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    /**
     * Copies a photo chosen for a contact into the app's own storage and
     * returns a URI for the copy.
     *
     * Storing the picker's URI did not work: a photo-picker grant is
     * temporary (persisting it is not supported on every device), and a
     * camera shot lives in the cache directory, which Android clears. Either
     * way the photo failed to load or vanished later. A private copy, scaled
     * to avatar size, is always readable.
     */
    fun storeContactPhoto(context: Context, contactId: String, source: Uri): Uri {
        val dir = File(context.filesDir, CONTACT_PHOTO_DIR)
        val prefix = contactId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "_"
        // A new name each time so image caches keyed on the URI show the new
        // photo instead of the one it replaces.
        val target = File(dir, prefix + System.currentTimeMillis() + ".jpg")
        ImageDownscaler.writeJpeg(context, source, target)
        dir.listFiles { f -> f.name.startsWith(prefix) && f != target }?.forEach { it.delete() }
        return Uri.fromFile(target)
    }

    fun deleteContactPhotos(context: Context, contactId: String) {
        val prefix = contactId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "_"
        File(context.filesDir, CONTACT_PHOTO_DIR)
            .listFiles { f -> f.name.startsWith(prefix) }
            ?.forEach { it.delete() }
    }

    private const val CONTACT_PHOTO_DIR = "contact_photos"

    fun takePersistableReadPermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}
