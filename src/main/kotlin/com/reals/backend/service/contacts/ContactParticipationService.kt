package com.reals.backend.service.contacts

import com.reals.backend.config.SharedContactsProperties
import com.reals.backend.repository.ContactParticipation
import com.reals.backend.repository.SharedContactRepository
import com.reals.backend.service.UserService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.util.UUID

enum class ContactSyncState { NOT_CONFIGURED, SYNCED, RESYNC_REQUIRED }
data class ContactStatus(
    val mutationRevision: UUID,
    val requiredConsentVersion: Int,
    val acceptedConsentVersion: Int?,
    val acceptedAt: OffsetDateTime?,
    val state: ContactSyncState,
    val syncedAt: OffsetDateTime?,
    val importAvailable: Boolean,
    val totalDistinctCount: Int?
)

data class ContactImportResult(val status: ContactStatus, val submittedCount: Int, val distinctCount: Int)

data class ContactAdditionResult(val status: ContactStatus, val submittedCount: Int, val addedDistinctCount: Int, val totalDistinctCount: Int)

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
        val (revision, row, count) = repository.ownerStatus(owner)
        val state = when {
            row?.keyVersion == null -> ContactSyncState.NOT_CONFIGURED
            row.keyVersion != settings.keyVersion || row.consentVersion != settings.consentVersion -> ContactSyncState.RESYNC_REQUIRED
            else -> ContactSyncState.SYNCED
        }
        return ContactStatus(
            mutationRevision = revision,
            requiredConsentVersion = settings.consentVersion,
            acceptedConsentVersion = row?.consentVersion,
            acceptedAt = row?.acceptedAt,
            state = state,
            syncedAt = row?.syncedAt,
            importAvailable = settings.enabled && settings.importEnabled,
            totalDistinctCount = when (state) {
                ContactSyncState.SYNCED -> count
                ContactSyncState.NOT_CONFIGURED -> 0
                ContactSyncState.RESYNC_REQUIRED -> null
            }
        )
    }

    fun accept(owner: UUID, version: Int, expected: UUID): ContactStatus {
        users.lockActiveUserOrThrow(owner, "Accept contact consent")
        requireImportEnabled()
        requireRevision(owner, expected)
        if (version != settings.consentVersion) consentRequired()
        if (repository.participation(owner)?.consentVersion != version) {
            repository.accept(owner, version, OffsetDateTime.now())
            repository.advanceRevision(owner)
        }
        return status(owner)
    }

    @Transactional(readOnly = true)
    fun requireImportConsent(owner: UUID, expected: UUID) {
        requireRevision(owner, expected)
        requireImportEnabled()
        if (repository.participation(owner)?.consentVersion != settings.consentVersion) consentRequired()
    }

    fun replace(owner: UUID, selection: ContactSelection, expected: UUID): ContactImportResult {
        val row = lockForImport(owner, expected)
        val selected = pseudonymizer.identifiers(selection)
        val previous = repository.identifiers(owner)
        if (row.keyVersion != settings.keyVersion || previous != selected) {
            val invalidate = row.keyVersion != settings.keyVersion || !selected.containsAll(previous)
            val toInsert = if (invalidate) selected else selected - previous
            persistChange(owner, toInsert, invalidate)
        }
        return ContactImportResult(status(owner), selection.numbers.size, selected.size)
    }

    fun add(owner: UUID, selection: ContactSelection, expected: UUID): ContactAdditionResult {
        val row = lockForImport(owner, expected)
        if (row.keyVersion != settings.keyVersion) {
            throw ContactRequestError(409, "CONTACT_FULL_REPLACEMENT_REQUIRED", "Synchronize the complete selection before adding contacts")
        }
        val selected = pseudonymizer.identifiers(selection)
        val previous = repository.identifiers(owner)
        val additions = selected - previous
        val total = previous.size + additions.size
        if (total > settings.maxDistinct) {
            throw ContactRequestError(413, "CONTACT_SET_LIMIT", "Combined selection exceeds the distinct number limit")
        }
        if (additions.isNotEmpty()) persistChange(owner, additions, invalidate = false)
        return ContactAdditionResult(status(owner), selection.numbers.size, additions.size, total)
    }

    private fun lockForImport(owner: UUID, expected: UUID): ContactParticipation {
        users.lockActiveUserOrThrow(owner, "Import contact selection")
        requireImportEnabled()
        requireRevision(owner, expected)
        val row = repository.participation(owner)
        if (row == null || row.consentVersion != settings.consentVersion) consentRequired()
        return row
    }

    private fun persistChange(owner: UUID, inserts: Set<ByteBuffer>, invalidate: Boolean) {
        val now = OffsetDateTime.now()
        if (!repository.recordReplacement(owner, now, settings.dailyReplacements)) {
            throw ContactRequestError(429, "CONTACT_REPLACEMENT_LIMIT", "Rolling 24-hour replacement quota reached")
        }
        repository.advanceRevision(owner)
        repository.synchronize(owner, inserts, settings.keyVersion, now, invalidate)
    }

    fun remove(owner: UUID, withdraw: Boolean) {
        users.lockActiveUserOrThrow(owner, "Remove contact participation data")
        repository.advanceRevision(owner)
        if (withdraw) repository.withdraw(owner) else repository.clear(owner)
    }

    private fun requireRevision(owner: UUID, expected: UUID) {
        if (repository.revision(owner) != expected) {
            throw ContactRequestError(409, "CONTACT_MUTATION_STALE", "Refresh shared-contact status and confirm your selection or consent again")
        }
    }

    private fun requireImportEnabled() {
        if (!settings.enabled || !settings.importEnabled) throw ContactRequestError(409, "CONTACT_IMPORT_DISABLED", "Contact import is unavailable")
    }

    private fun consentRequired(): Nothing =
        throw ContactRequestError(409, "CONTACT_CONSENT_REQUIRED", "Accept the current shared-contact consent version")
}
