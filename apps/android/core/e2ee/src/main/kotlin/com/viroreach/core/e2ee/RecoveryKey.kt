package com.viroreach.core.e2ee

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The key to someone's own backup.
 *
 * It is 32 random bytes, shown as sixteen groups of four characters so it can
 * be written on paper and typed back in. There is no password to guess at and
 * no hint to lose: the key *is* the secret, it exists only where the person
 * puts it, and Viro cannot help anyone who loses it. That is the honest cost
 * of a backup the server cannot read, and the app says so in those words.
 */
object RecoveryKey {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    /**
     * Crockford's base 32 without I, L, O or U: no character can be mistaken
     * for another when someone reads a key off a piece of paper.
     */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** A fresh key, as the person will see it. */
    fun generate(): String {
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        return format(bytes)
    }

    /** Groups of four, which is how people copy things accurately. */
    fun format(bytes: ByteArray): String =
        bytes.joinToString("") { byte ->
            val v = byte.toInt() and 0xFF
            "${ALPHABET[v shr 4]}${ALPHABET[v and 0x0F]}"
        }.chunked(4).joinToString("-")

    /**
     * The bytes behind a key as it was typed. Spaces, dashes and lower case
     * are all forgiven; anything else is not a key.
     */
    fun parse(typed: String): ByteArray? {
        val clean = typed.uppercase().filter { it != '-' && !it.isWhitespace() }
        if (clean.length != KEY_BYTES * 2) return null
        val out = ByteArray(KEY_BYTES)
        for (i in 0 until KEY_BYTES) {
            val high = ALPHABET.indexOf(clean[i * 2])
            val low = ALPHABET.indexOf(clean[i * 2 + 1])
            if (high < 0 || low < 0 || high > 0x0F || low > 0x0F) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    /** True when this could be a key at all, before anything is attempted with it. */
    fun looksValid(typed: String): Boolean = parse(typed) != null

    /**
     * Seals an archive under [key]. The nonce is written in front of the
     * ciphertext, because the person keeps a key, not a key and a nonce.
     */
    fun seal(plain: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        }
        return iv + cipher.doFinal(plain)
    }

    /**
     * Opens an archive. Null when the key is wrong or the bytes were changed —
     * GCM tells the difference, which is why the app can say "that key does
     * not open this backup" rather than restoring nonsense.
     */
    fun open(sealed: ByteArray, key: ByteArray): ByteArray? = runCatching {
        if (sealed.size <= IV_BYTES) return null
        val iv = sealed.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        }
        cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }.getOrNull()

    /** For storing the key on this phone, where it is kept so backups can run unattended. */
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun decode(value: String): ByteArray? = runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull()
}
