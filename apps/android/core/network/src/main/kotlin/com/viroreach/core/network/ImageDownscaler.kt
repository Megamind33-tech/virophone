package com.viroreach.core.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File

/**
 * Decodes a picked or captured image, scales it down, turns it upright and
 * writes it out as JPEG.
 *
 * Used for anything shown as an avatar. A camera frame is 3-8MB and a few
 * thousand pixels wide; an avatar is drawn at about 96dp, so the full frame
 * only costs memory, storage and — for uploads — mobile data.
 *
 * inJustDecodeBounds + inSampleSize means the full-size bitmap is never
 * allocated; a 12MP photo would otherwise risk OutOfMemory on a low-end phone.
 */
object ImageDownscaler {
    const val AVATAR_MAX_EDGE_PX = 1024
    private const val JPEG_QUALITY = 85

    fun writeJpeg(
        context: Context,
        source: Uri,
        destination: File,
        maxEdgePx: Int = AVATAR_MAX_EDGE_PX,
    ) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode ALWAYS returns null — that is how
        // inJustDecodeBounds works; the answer is in `bounds`. Chaining
        // `?: throw` onto it rejected every photo as unreadable, so the stream
        // is checked on its own and the decode result ignored.
        val stream = resolver.openInputStream(source)
            ?: throw IllegalStateException("Could not read the selected photo")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("That file does not look like an image")
        }

        var sample = 1
        while (bounds.outWidth / sample > maxEdgePx * 2 || bounds.outHeight / sample > maxEdgePx * 2) {
            sample *= 2
        }
        val decoded = (
            resolver.openInputStream(source)
                ?: throw IllegalStateException("Could not read the selected photo")
            ).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("This phone can't open that photo's format. Try another photo.")

        // Phone cameras store pixels in sensor order and record the rotation
        // in EXIF; BitmapFactory ignores it, so without this a portrait photo
        // lands on its side.
        val degrees = runCatching {
            resolver.openInputStream(source)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        val longest = maxOf(decoded.width, decoded.height)
        val scale = if (longest > maxEdgePx) maxEdgePx.toFloat() / longest else 1f
        val output = if (scale < 1f || degrees != 0f) {
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postRotate(degrees)
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else {
            decoded
        }

        destination.parentFile?.mkdirs()
        destination.outputStream().use { out ->
            output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        if (output !== decoded) output.recycle()
        decoded.recycle()
    }
}
