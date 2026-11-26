package com.reals.backend.service.contacts

import com.reals.backend.config.SharedContactsProperties
import com.reals.backend.repository.SharedContactRepository
import com.reals.backend.service.UserService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

enum class ContactSyncState { NOT_CONFIGURED, SYNCED, RESYNC_REQUIRED }
data class ContactStatus(
    val requiredConsentVersion: Int,
    val acceptedConsentVersion: Int?,
    val acceptedAt: OffsetDateTime?,
    val state: ContactSyncState,
    val syncedAt: OffsetDateTime?,
    val importAvailable: Boolean
)

data class ContactImportResult(val status: ContactStatus, val submittedCount: Int, val distinctCount: Int)

@Service
@Transactional
class ContactParticipationService(
    private val settings: SharedContactsProperties,
    private val repository: SharedContactRepository,
    private val users: UserService,
    private val pseudonymizer: ContactPseudonymizer
) {
    @Transactional(readOnly = true)
    fun status(owner: UUID): ContactStatus {
        val row = repository.participation(owner)
        val state = when {
            row?.keyVersion == null -> ContactSyncState.NOT_CONFIGURED
            row.keyVersion != settings.keyVersion || row.consentVersion != settings.consentVersion -> ContactSyncState.RESYNC_REQUIRED
            else -> ContactSyncState.SYNCED
        }
        return ContactStatus(
            requiredConsentVersion = settings.consentVersion,
            acceptedConsentVersion = row?.consentVersion,
            acceptedAt = row?.acceptedAt,
            state = state,
            syncedAt = row?.syncedAt,
            importAvailable = settings.enabled && settings.importEnabled
        )
    }

    fun accept(owner: UUID, version: Int): ContactStatus {
        users.lockActiveUserOrThrow(owner, "Accept contact consent")
        requireImportEnabled()
        if (version != settings.consentVersion) consentRequired()
        if (repository.participation(owner)?.consentVersion != version) repository.accept(owner, version, OffsetDateTime.now())
        return status(owner)
    }

    @Transactional(readOnly = true)
    fun requireImportConsent(owner: UUID) {
        requireImportEnabled()
        if (repository.participation(owner)?.consentVersion != settings.consentVersion) consentRequired()
    }

    fun replace(owner: UUID, selection: ContactSelection): ContactImportResult {
        users.lockActiveUserOrThrow(owner, "Replace contact selection")
        requireImportEnabled()
        val row = repository.participation(owner)
        if (row == null || row.consentVersion != settings.consentVersion) consentRequired()
        val selected = pseudonymizer.identifiers(selection)
        val previous = repository.identifiers(owner)
        if (row.keyVersion != settings.keyVersion || previous != selected) {
            val now = OffsetDateTime.now()
            if (!repository.recordReplacement(owner, now, settings.dailyReplacements)) {
                throw ContactRequestError(429, "CONTACT_REPLACEMENT_LIMIT", "Rolling 24-hour replacement quota reached")
            }
            val invalidate = row.keyVersion != settings.keyVersion || !selected.containsAll(previous)
            val toInsert = if (invalidate) selected else selected - previous
            repository.synchronize(owner, toInsert, settings.keyVersion, now, invalidate)
        }
        return ContactImportResult(status(owner), selection.numbers.size, selected.size)
    }

    fun remove(owner: UUID, withdraw: Boolean) {
        users.lockActiveUserOrThrow(owner, "Remove contact participation data")
        if (withdraw) repository.withdraw(owner) else repository.clear(owner)
    }

    private fun requireImportEnabled() {
        if (!settings.enabled || !settings.importEnabled) throw ContactRequestError(409, "CONTACT_IMPORT_DISABLED", "Contact import is unavailable")
    }

    private fun consentRequired(): Nothing =
        throw ContactRequestError(409, "CONTACT_CONSENT_REQUIRED", "Accept the current shared-contact consent version")
}
