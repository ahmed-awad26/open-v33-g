package com.opencontacts.core.common

/**
 * PhoneNormalizer — canonical representation of phone numbers.
 *
 * Handles:
 *   - Country code expansion (default: Egypt +20)
 *   - Stripping formatting characters
 *   - Deduplication across different visual formats of the same number
 *
 * Usage:
 *   PhoneNormalizer.normalize("+20 100 000 0000")  → "+201000000000"
 *   PhoneNormalizer.normalize("01000000000")        → "+201000000000"  (EG default)
 *   PhoneNormalizer.isSameNumber(a, b)              → true/false
 */
object PhoneNormalizer {

    /** Default country calling code — Egypt. Configurable via [setDefaultCountryCode]. */
    @Volatile private var defaultCountryCode: String = "20"
    @Volatile private var defaultMobilePrefix: String = "0"  // local trunk prefix

    fun setDefaultCountryCode(isoCode: String) {
        defaultCountryCode = isoCode.trimStart('+', '0')
    }

    /**
     * Normalize a raw phone number to E.164 format (+CCNNNN...).
     * Returns null if the input is clearly not a phone number.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        // Strip all formatting except leading +
        val stripped = raw.trim()
            .replace(Regex("[\\s\\-().\\[\\]/]"), "")
            .replace(Regex("[^+\\d]"), "")

        if (stripped.length < 4) return null

        return when {
            // Already has country code
            stripped.startsWith("+") -> stripped.ifBlank { null }

            // Starts with 00 (international dialing prefix)
            stripped.startsWith("00") -> "+${stripped.drop(2)}"

            // Local number with trunk prefix (e.g. "0100" for Egypt)
            stripped.startsWith(defaultMobilePrefix) && stripped.length >= 9 ->
                "+${defaultCountryCode}${stripped.drop(defaultMobilePrefix.length)}"

            // Plain digits — assume local, add country code
            stripped.all { it.isDigit() } && stripped.length >= 7 ->
                "+${defaultCountryCode}${stripped}"

            else -> null
        }
    }

    /**
     * Returns true if two raw numbers refer to the same line.
     */
    fun isSameNumber(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val na = normalize(a) ?: return a.trim() == b.trim()
        val nb = normalize(b) ?: return a.trim() == b.trim()
        return na == nb
    }

    /**
     * Canonical key for deduplication maps (digits only, no leading zeros).
     */
    fun canonicalKey(raw: String?): String? {
        val n = normalize(raw) ?: return null
        return n.filter(Char::isDigit)
    }

    /**
     * Format a normalized number for display (simple, no libphonenumber dependency).
     * "+201001234567" → "+20 100 123 4567"
     */
    fun formatForDisplay(normalized: String?): String {
        if (normalized.isNullOrBlank()) return ""
        if (!normalized.startsWith("+")) return normalized
        // Simple grouping: +CC NNNNNNNNN
        val cc = normalized.take(3) // e.g. "+20"
        val rest = normalized.drop(3)
        return when {
            rest.length == 10 -> "$cc ${rest.take(3)} ${rest.drop(3).take(3)} ${rest.drop(6)}"
            rest.length == 9  -> "$cc ${rest.take(3)} ${rest.drop(3)}"
            else -> normalized
        }
    }
}
