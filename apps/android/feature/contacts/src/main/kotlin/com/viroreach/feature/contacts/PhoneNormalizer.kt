package com.viroreach.feature.contacts

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.NumberParseException

/**
 * E.164 normalization via libphonenumber (Google).
 * Supports international numbers; default region configurable (ZM for Zambia).
 */
object PhoneNormalizer {
    private val util = PhoneNumberUtil.getInstance()

    fun normalizeToE164(phone: String, defaultRegion: String = "ZM"): String? {
        val trimmed = phone.trim()
        if (trimmed.isEmpty()) return null
        try {
            val parsed = util.parse(trimmed, defaultRegion)
            if (!util.isValidNumber(parsed)) return null
            return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (_: NumberParseException) {
            return null
        }
    }
}
