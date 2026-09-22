package com.viroreach.app.moments.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * The picture that came with the song.
 *
 * Taken from the file itself: ExoPlayer already parses the embedded artwork
 * while it prepares, so this costs no extra request and nothing is fetched
 * that was not being fetched anyway. Plenty of files carry none, which is why
 * every caller has to cope with null rather than assume a picture.
 */
class SongArtwork(
    /** Full size, for the record label. */
    val full: ImageBitmap,
    /**
     * A deliberately tiny copy. Drawn scaled up, the bilinear filtering does
     * the blurring — which works on every Android this app supports, where
     * Modifier.blur would only work from 12 onwards.
     */
    val blurred: ImageBitmap,
)

/**
 * The artwork of whatever is playing, or null.
 *
 * Re-reads whenever the player's metadata changes, which is what happens when
 * the room moves to another song.
 */
@Composable
fun rememberSongArtwork(exo: ExoPlayer?, mediaId: String?): SongArtwork? {
    var artwork by remember(exo, mediaId) { mutableStateOf<SongArtwork?>(null) }
    DisposableEffect(exo, mediaId) {
        if (exo == null) {
            artwork = null
            onDispose {}
        } else {
            fun read(metadata: MediaMetadata) {
                artwork = decodeArtwork(metadata.artworkData)
            }
            read(exo.mediaMetadata)
            val listener = object : Player.Listener {
                override fun onMediaMetadataChanged(metadata: MediaMetadata) = read(metadata)
            }
            exo.addListener(listener)
            onDispose { exo.removeListener(listener) }
        }
    }
    return artwork
}

/** Decodes embedded artwork, and gives up quietly on anything unreadable. */
private fun decodeArtwork(bytes: ByteArray?): SongArtwork? {
    if (bytes == null || bytes.isEmpty()) return null
    return runCatching {
        // Sampled down on the way in: album art is often far larger than the
        // 180dp record it ends up inside, and decoding it whole is wasted work
        // on a cheap phone.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / TARGET_PX)
        val options = BitmapFactory.Options().apply { inSampleSize = Integer.highestOneBit(sample) }
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@runCatching null
        val tiny = Bitmap.createScaledBitmap(full, BLUR_PX, BLUR_PX, true)
        SongArtwork(full.asImageBitmap(), tiny.asImageBitmap())
    }.getOrNull()
}

/** Roughly the record's size on a dense screen; more detail than that is thrown away. */
private const val TARGET_PX = 540
/** Small enough that scaling it back up is a blur, large enough to keep the colours. */
private const val BLUR_PX = 16
