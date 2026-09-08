package com.reals.backend.service

import com.reals.backend.config.legal.LegalDocumentDefinition
import com.reals.backend.config.legal.LegalDocumentProperties
import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import com.reals.backend.domain.UserLegalDocumentAction
import com.reals.backend.repository.UserLegalDocumentActionRepository
import com.reals.backend.repository.UserRepository
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class LegalDocumentService(
    private val properties: LegalDocumentProperties,
    private val actionRepository: UserLegalDocumentActionRepository,
    private val userRepository: UserRepository,
    private val auditEventService: AuditEventService
) {

    data class LegalStatus(
        val requirementsSatisfied: Boolean,
        val documents: List<LegalDocumentStatus>
    )

    data class LegalDocumentStatus(
        val document: LegalDocumentDefinition,
        val recordedAction: UserLegalDocumentAction?,
        val satisfied: Boolean
    )

    data class RecordActionResult(
        val action: UserLegalDocumentAction,
        val created: Boolean
    )

    fun currentDocuments(): List<LegalDocumentDefinition> =
        properties.currentDocuments()

    @Transactional(readOnly = true)
    fun getStatus(userId: UUID): LegalStatus {
        val recordedByCurrentDocument =
            actionRepository.findByUserId(userId)
                .associateBy { it.documentType to it.documentVersion }

        val documentStatuses = currentDocuments().map { document ->
            val recorded = recordedByCurrentDocument[document.type to document.version]
            val satisfied = recorded != null &&
                recorded.action == document.requiredAction &&
                recorded.documentContentSha256 == document.contentSha256

            LegalDocumentStatus(
                document = document,
                recordedAction = recorded,
                satisfied = satisfied
            )
        }

        return LegalStatus(
            requirementsSatisfied = documentStatuses.all { it.satisfied },
            documents = documentStatuses
        )
    }

    @Transactional
    fun recordAction(
        userId: UUID,
        documentType: LegalDocumentType,
        documentVersion: String,
        action: LegalDocumentAction
    ): RecordActionResult {
        val document = currentDocumentOrThrow(documentType)

        if (documentVersion != document.version) {
            throw DomainConflictException(
                code = DomainErrorCode.LEGAL_DOCUMENT_VERSION_NOT_CURRENT,
                message = "Legal document version is not current"
            )
        }

        if (action != document.requiredAction) {
            throw DomainBadRequestException(
                code = DomainErrorCode.LEGAL_DOCUMENT_ACTION_INVALID,
                message = "Legal document action is invalid"
            )
        }

        val lockedUsers = userRepository.findAllByIdForUpdate(listOf(userId))
        if (lockedUsers.none { it.id == userId }) {
            throw DomainNotFoundException(
                code = DomainErrorCode.USER_NOT_FOUND,
                message = "User was not found"
            )
        }

        actionRepository.findByUserIdAndDocumentTypeAndDocumentVersion(
            userId = userId,
            documentType = documentType,
            documentVersion = documentVersion
        )?.let { existing ->
            validateExistingAction(
                existing = existing,
                requestedAction = action,
                currentContentSha256 = document.contentSha256
            )
            return RecordActionResult(action = existing, created = false)
        }

        val recorded = actionRepository.saveAndFlush(
            UserLegalDocumentAction(
                userId = userId,
                documentType = documentType,
                documentVersion = documentVersion,
                documentContentSha256 = document.contentSha256,
                action = action
            )
        )

        auditEventService.record(
            eventType = AuditEventType.LEGAL_DOCUMENT_ACTION_RECORDED,
            aggregateType = AuditAggregateType.USER,
            aggregateId = userId,
            actorUserId = userId,
            metadata = mapOf(
                "documentType" to documentType.name,
                "documentVersion" to documentVersion,
                "documentContentSha256" to recorded.documentContentSha256,
                "action" to action.name
            )
        )

        return RecordActionResult(action = recorded, created = true)
    }

    private fun currentDocumentOrThrow(documentType: LegalDocumentType): LegalDocumentDefinition =
        currentDocuments().firstOrNull { it.type == documentType }
            ?: throw DomainNotFoundException(
                code = DomainErrorCode.LEGAL_DOCUMENT_NOT_FOUND,
                message = "Legal document was not found"
            )

    private fun validateExistingAction(
        existing: UserLegalDocumentAction,
        requestedAction: LegalDocumentAction,
        currentContentSha256: String
    ) {
        if (existing.action != requestedAction) {
            throw DomainConflictException(
                code = DomainErrorCode.LEGAL_DOCUMENT_VERSION_NOT_CURRENT,
                message = "Existing legal document action differs from requested action"
            )
        }

        val existingContentSha256 = existing.documentContentSha256
            ?: throw IllegalStateException(
                "Existing legal document action for userId=${existing.userId}, " +
                    "documentType=${existing.documentType}, documentVersion=${existing.documentVersion} " +
                    "has no documentContentSha256. Configure a new legal document version for BACK-7 rollout."
            )

        if (existingContentSha256 != currentContentSha256) {
            throw IllegalStateException(
                "Existing legal document action for userId=${existing.userId}, " +
                    "documentType=${existing.documentType}, documentVersion=${existing.documentVersion} " +
                    "has documentContentSha256=$existingContentSha256 but current configuration has " +
                    "contentSha256=$currentContentSha256. Publish and configure a new legal document version."
            )
        }
    }
}
