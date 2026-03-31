package com.opencontacts.core.common

import com.opencontacts.core.model.ContactSummary
import com.opencontacts.core.model.allPhoneNumbers

object SmartMatchEngine {
    fun guessContactName(number: String?, contacts: List<ContactSummary>): String? {
        val normalized = PhoneNumberNormalizer.normalize(number)
        if (normalized.isBlank()) return null
        val exact = contacts.firstOrNull { contact -> contact.allPhoneNumbers().any { PhoneNumberNormalizer.normalize(it.value) == normalized } }
        if (exact != null) return exact.displayName
        val partial = contacts.firstOrNull { contact -> contact.allPhoneNumbers().any { PhoneNumberNormalizer.maybeMatches(it.value, normalized) } }
        return partial?.displayName
    }
}
