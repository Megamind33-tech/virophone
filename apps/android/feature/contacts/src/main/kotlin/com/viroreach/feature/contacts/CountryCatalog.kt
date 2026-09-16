package com.viroreach.feature.contacts

import com.google.i18n.phonenumbers.PhoneNumberUtil

data class CountryOption(
    val iso2: String,
    val name: String,
    val dialCode: String,
) {
    val flagEmoji: String get() = isoToFlag(iso2)
}

/**
 * Dial-code catalog for the login country picker. Identity remains E.164;
 * the selected ISO region is only the default for national-number parsing.
 */
object CountryCatalog {
    val all: List<CountryOption> = listOf(
        CountryOption("AF", "Afghanistan", "+93"),
        CountryOption("AL", "Albania", "+355"),
        CountryOption("DZ", "Algeria", "+213"),
        CountryOption("AR", "Argentina", "+54"),
        CountryOption("AU", "Australia", "+61"),
        CountryOption("AT", "Austria", "+43"),
        CountryOption("BD", "Bangladesh", "+880"),
        CountryOption("BE", "Belgium", "+32"),
        CountryOption("BJ", "Benin", "+229"),
        CountryOption("BO", "Bolivia", "+591"),
        CountryOption("BR", "Brazil", "+55"),
        CountryOption("BG", "Bulgaria", "+359"),
        CountryOption("BF", "Burkina Faso", "+226"),
        CountryOption("BI", "Burundi", "+257"),
        CountryOption("KH", "Cambodia", "+855"),
        CountryOption("CM", "Cameroon", "+237"),
        CountryOption("CA", "Canada", "+1"),
        CountryOption("CL", "Chile", "+56"),
        CountryOption("CN", "China", "+86"),
        CountryOption("CO", "Colombia", "+57"),
        CountryOption("CD", "Congo (DRC)", "+243"),
        CountryOption("CG", "Congo (Republic)", "+242"),
        CountryOption("CR", "Costa Rica", "+506"),
        CountryOption("CI", "Côte d'Ivoire", "+225"),
        CountryOption("HR", "Croatia", "+385"),
        CountryOption("CZ", "Czechia", "+420"),
        CountryOption("DK", "Denmark", "+45"),
        CountryOption("EG", "Egypt", "+20"),
        CountryOption("ET", "Ethiopia", "+251"),
        CountryOption("FI", "Finland", "+358"),
        CountryOption("FR", "France", "+33"),
        CountryOption("DE", "Germany", "+49"),
        CountryOption("GH", "Ghana", "+233"),
        CountryOption("GR", "Greece", "+30"),
        CountryOption("GT", "Guatemala", "+502"),
        CountryOption("GN", "Guinea", "+224"),
        CountryOption("HK", "Hong Kong", "+852"),
        CountryOption("HU", "Hungary", "+36"),
        CountryOption("IN", "India", "+91"),
        CountryOption("ID", "Indonesia", "+62"),
        CountryOption("IE", "Ireland", "+353"),
        CountryOption("IL", "Israel", "+972"),
        CountryOption("IT", "Italy", "+39"),
        CountryOption("JP", "Japan", "+81"),
        CountryOption("JO", "Jordan", "+962"),
        CountryOption("KE", "Kenya", "+254"),
        CountryOption("KR", "South Korea", "+82"),
        CountryOption("KW", "Kuwait", "+965"),
        CountryOption("LS", "Lesotho", "+266"),
        CountryOption("LR", "Liberia", "+231"),
        CountryOption("LY", "Libya", "+218"),
        CountryOption("MW", "Malawi", "+265"),
        CountryOption("MY", "Malaysia", "+60"),
        CountryOption("ML", "Mali", "+223"),
        CountryOption("MX", "Mexico", "+52"),
        CountryOption("MA", "Morocco", "+212"),
        CountryOption("MZ", "Mozambique", "+258"),
        CountryOption("NA", "Namibia", "+264"),
        CountryOption("NL", "Netherlands", "+31"),
        CountryOption("NZ", "New Zealand", "+64"),
        CountryOption("NG", "Nigeria", "+234"),
        CountryOption("NO", "Norway", "+47"),
        CountryOption("PK", "Pakistan", "+92"),
        CountryOption("PE", "Peru", "+51"),
        CountryOption("PH", "Philippines", "+63"),
        CountryOption("PL", "Poland", "+48"),
        CountryOption("PT", "Portugal", "+351"),
        CountryOption("QA", "Qatar", "+974"),
        CountryOption("RO", "Romania", "+40"),
        CountryOption("RU", "Russia", "+7"),
        CountryOption("RW", "Rwanda", "+250"),
        CountryOption("SA", "Saudi Arabia", "+966"),
        CountryOption("SN", "Senegal", "+221"),
        CountryOption("RS", "Serbia", "+381"),
        CountryOption("SL", "Sierra Leone", "+232"),
        CountryOption("SG", "Singapore", "+65"),
        CountryOption("ZA", "South Africa", "+27"),
        CountryOption("ES", "Spain", "+34"),
        CountryOption("LK", "Sri Lanka", "+94"),
        CountryOption("SE", "Sweden", "+46"),
        CountryOption("CH", "Switzerland", "+41"),
        CountryOption("TZ", "Tanzania", "+255"),
        CountryOption("TH", "Thailand", "+66"),
        CountryOption("TR", "Turkey", "+90"),
        CountryOption("UG", "Uganda", "+256"),
        CountryOption("UA", "Ukraine", "+380"),
        CountryOption("AE", "United Arab Emirates", "+971"),
        CountryOption("GB", "United Kingdom", "+44"),
        CountryOption("US", "United States", "+1"),
        CountryOption("VN", "Vietnam", "+84"),
        CountryOption("ZM", "Zambia", "+260"),
        CountryOption("ZW", "Zimbabwe", "+263"),
    ).sortedBy { it.name }

    fun default(): CountryOption = byIso("ZM")

    fun byIso(iso2: String): CountryOption =
        all.firstOrNull { it.iso2.equals(iso2, ignoreCase = true) } ?: default()

    fun fromE164(e164: String): CountryOption {
        return try {
            val parsed = PhoneNumberUtil.getInstance().parse(e164, null)
            val region = PhoneNumberUtil.getInstance().getRegionCodeForNumber(parsed)
            if (region.isNullOrBlank()) default() else byIso(region)
        } catch (_: Exception) {
            default()
        }
    }

    fun search(query: String): List<CountryOption> {
        val q = query.trim()
        if (q.isEmpty()) return all
        val lower = q.lowercase()
        val digits = q.filter { it.isDigit() || it == '+' }
        return all.filter { country ->
            country.name.lowercase().contains(lower) ||
                country.iso2.lowercase().contains(lower) ||
                (digits.isNotEmpty() && country.dialCode.contains(digits))
        }
    }
}

internal fun isoToFlag(iso2: String): String {
    if (iso2.length != 2) return ""
    val upper = iso2.uppercase()
    val first = Character.codePointAt(upper, 0) - 'A'.code + 0x1F1E6
    val second = Character.codePointAt(upper, 1) - 'A'.code + 0x1F1E6
    return String(intArrayOf(first, second), 0, 2)
}
