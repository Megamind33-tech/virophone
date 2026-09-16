package com.viroreach.feature.contacts

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/**
 * Canonical identity is always E.164 via [PhoneNormalizer].
 * Display formatting is derived only — never stored as identity.
 */
object PhoneNumberFormatter {
    private val util = PhoneNumberUtil.getInstance()

    /** Raw dial-pad input: digits plus * and # only. */
    fun sanitizeDialInput(raw: String): String =
        raw.filter { it.isDigit() || it == '*' || it == '#' }

    /** National/significant digits for auth entry (no spaces or punctuation). */
    fun sanitizeAuthDigits(raw: String): String = raw.filter { it.isDigit() }

    /**
     * Format for display. Input may be partial digits or full E.164.
     * Output is stable for the same underlying digit sequence.
     */
    fun formatForDisplay(rawDigits: String, defaultRegion: String = "ZM"): String {
        val digits = sanitizeAuthDigits(rawDigits)
        if (digits.isEmpty()) return ""
        val e164 = PhoneNormalizer.normalizeToE164(digits, defaultRegion)
            ?: PhoneNormalizer.normalizeToE164("+$digits", defaultRegion)
        if (e164 != null) {
            return formatE164International(e164)
        }
        return groupPartialDigits(digits)
    }

    fun formatE164International(e164: String): String {
        return try {
            val parsed = util.parse(e164, null)
            util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        } catch (_: Exception) {
            e164
        }
    }

    /** Stable partial grouping while user is still typing. */
    fun groupPartialDigits(digits: String): String {
        if (digits.length <= 3) return digits
        if (digits.length <= 6) return "${digits.take(3)} ${digits.drop(3)}"
        if (digits.length <= 9) {
            return "${digits.take(3)} ${digits.drop(3).take(3)} ${digits.drop(6)}"
        }
        return "${digits.take(3)} ${digits.drop(3).take(3)} ${digits.drop(6).take(3)} ${digits.drop(9)}"
    }

    fun canonicalE164(rawDigits: String, defaultRegion: String = "ZM"): String? =
        PhoneNormalizer.normalizeToE164(sanitizeAuthDigits(rawDigits), defaultRegion)

    /** National significant digits for pre-filling auth after a session restore miss. */
    fun nationalDigitsFromE164(e164: String): String = try {
        val parsed = util.parse(e164, null)
        parsed.nationalNumber.toString()
    } catch (_: Exception) {
        sanitizeAuthDigits(e164.removePrefix("+"))
    }
}
