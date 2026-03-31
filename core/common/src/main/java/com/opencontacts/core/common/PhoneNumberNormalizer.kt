package com.opencontacts.core.common

object PhoneNumberNormalizer {
    fun normalize(raw: String?): String {
        val digits = raw.orEmpty().filter(Char::isDigit)
        if (digits.isBlank()) return ""
        return when {
            digits.length > 10 -> digits.takeLast(10)
            else -> digits
        }
    }

    fun maybeMatches(a: String?, b: String?): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isBlank() || nb.isBlank()) return false
        return na == nb || na.endsWith(nb) || nb.endsWith(na)
    }
}
