package com.opencontacts.app

import com.opencontacts.core.model.ContactSummary
import com.opencontacts.core.model.allPhoneNumbers
import com.opencontacts.domain.contacts.ContactRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object TelecomContactResolver {
    private val mutex = Mutex()
    private var cachedVaultId: String? = null
    private var cachedAt: Long = 0L
    private var byNormalizedNumber: Map<String, ContactSummary> = emptyMap()

    suspend fun resolve(vaultId: String, repository: ContactRepository, rawNumber: String): ContactSummary? {
        ensureFresh(vaultId, repository)
        val normalized = normalizeIncomingNumber(rawNumber)
        if (normalized.isBlank()) return null
        return byNormalizedNumber[normalized]
    }

    suspend fun warm(vaultId: String, repository: ContactRepository) {
        ensureFresh(vaultId, repository)
    }

    private suspend fun ensureFresh(vaultId: String, repository: ContactRepository) {
        if (cachedVaultId == vaultId && System.currentTimeMillis() - cachedAt < 20_000L && byNormalizedNumber.isNotEmpty()) return
        mutex.withLock {
            if (cachedVaultId == vaultId && System.currentTimeMillis() - cachedAt < 20_000L && byNormalizedNumber.isNotEmpty()) return
            val contacts = repository.observeContacts(vaultId).first()
            byNormalizedNumber = contacts
                .flatMap { contact -> contact.allPhoneNumbers().map { phone -> normalizeIncomingNumber(phone.value) to contact } }
                .filter { it.first.isNotBlank() }
                .associate { it }
            cachedVaultId = vaultId
            cachedAt = System.currentTimeMillis()
        }
    }
}
