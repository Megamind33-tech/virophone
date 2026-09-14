package com.viroreach.feature.contacts

/**
 * Client-side E.164 phone normalization.
 * Phase 0: basic normalization. Production should use libphonenumber-android.
 */
object PhoneNormalizer {
    fun normalizeToE164(phone: String, defaultCountryCode: String = "+260"): String? {
        val cleaned = phone.replace(Regex("[\\s\\-\\(\\)\\.]"), "")
        if (cleaned.startsWith("+")) {
            val digits = cleaned.substring(1)
            return if (digits.matches(Regex("\\d{7,15}"))) "+$digits" else null
        }
        if (cleaned.startsWith("0")) {
            val withoutZero = cleaned.substring(1)
            val cc = defaultCountryCode.removePrefix("+")
            return if (withoutZero.matches(Regex("\\d{7,14}"))) "+$cc$withoutZero" else null
        }
        return null
    }
}
