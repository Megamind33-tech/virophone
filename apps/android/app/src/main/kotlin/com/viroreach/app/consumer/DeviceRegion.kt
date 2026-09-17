package com.viroreach.app.consumer

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Best-effort ISO 3166-1 alpha-2 region for parsing phone numbers that arrive
 * without a country code (device contacts, manual dial entry). Falls back
 * from the SIM's registered country, to the network operator's country, to
 * the device locale, to a neutral default — never hardcode a single country
 * here again: that previously made every non-Zambian number fail to parse,
 * which broke contact discovery (empty "on Viro" list) and messaging
 * ("isn't on Viro yet" for accounts that actually are).
 */
object DeviceRegion {
    fun current(context: Context): String {
        val telephony = context.applicationContext
            .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val simIso = telephony?.simCountryIso?.takeIf { it.isNotBlank() }
        val networkIso = telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
        val localeIso = Locale.getDefault().country.takeIf { it.isNotBlank() }
        return (simIso ?: networkIso ?: localeIso ?: "US").uppercase(Locale.ROOT)
    }
}
