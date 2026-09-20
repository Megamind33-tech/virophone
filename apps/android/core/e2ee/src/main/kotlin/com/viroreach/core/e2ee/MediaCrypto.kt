package com.viroreach.core.e2ee

import android.util.Base64
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The key to one file, carried inside the encrypted message that sent it. */
data class MediaKey(val key: String, val iv: String)

/**
 * Files under end-to-end encryption.
 *
 * Each file gets its own key, used once. The server stores the result as
 * bytes it cannot read, and the key travels inside the encrypted message —
 * so a file is exactly as private as the message that carried it.
 *
 * Streamed rather than held in memory: a document can be 25 MB, and phones
 * that run Viro do not have that to spare.
 */
object MediaCrypto {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Encrypts [plain] into [sealed], returning the key needed to open it. */
    fun seal(plain: File, sealed: File): MediaKey {
        val random = SecureRandom()
        val key = ByteArray(KEY_BITS / 8).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        }
        plain.inputStream().use { input ->
            CipherOutputStream(sealed.outputStream(), cipher).use { output -> input.copyTo(output) }
        }
        return MediaKey(key.b64(), iv.b64())
    }

    /**
     * Decrypts [sealed] into [plain]. False when the key is wrong or the bytes
     * were tampered with — GCM checks that, which is why it is used here.
     */
    fun open(sealed: File, plain: File, mediaKey: MediaKey): Boolean = runCatching {
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(mediaKey.key.b64Bytes(), "AES"),
                GCMParameterSpec(TAG_BITS, mediaKey.iv.b64Bytes()),
            )
        }
        CipherInputStream(sealed.inputStream(), cipher).use { input ->
            plain.outputStream().use { output -> input.copyTo(output) }
        }
        true
    }.getOrElse {
        plain.delete()
        false
    }

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.b64Bytes(): ByteArray = Base64.decode(this, Base64.DEFAULT)
}
