package com.reals.backend.controller.dto

import com.reals.backend.config.legal.LegalDocumentDefinition
import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import com.reals.backend.domain.UserLegalDocumentAction
import com.reals.backend.service.LegalDocumentService
import java.time.OffsetDateTime
import java.util.UUID

data class CurrentLegalDocumentsResponse(
    val documents: List<CurrentLegalDocumentResponse>
)

data class CurrentLegalDocumentResponse(
    val type: LegalDocumentType,
    val version: String,
    val url: String,
    val requiredAction: LegalDocumentAction
) {
    companion object {
        fun from(document: LegalDocumentDefinition): CurrentLegalDocumentResponse =
            CurrentLegalDocumentResponse(
                type = document.type,
                version = document.version,
                url = document.url,
                requiredAction = document.requiredAction
            )
    }
}

data class LegalStatusResponse(
    val requirementsSatisfied: Boolean,
    val documents: List<LegalDocumentStatusResponse>
) {
    companion object {
        fun from(status: LegalDocumentService.LegalStatus): LegalStatusResponse =
            LegalStatusResponse(
                requirementsSatisfied = status.requirementsSatisfied,
                documents = status.documents.map { LegalDocumentStatusResponse.from(it) }
            )
    }
}

data class LegalDocumentStatusResponse(
    val type: LegalDocumentType,
    val version: String,
    val requiredAction: LegalDocumentAction,
    val recordedAction: LegalDocumentAction?,
    val actedAt: OffsetDateTime?,
    val satisfied: Boolean
) {
    companion object {
        fun from(status: LegalDocumentService.LegalDocumentStatus): LegalDocumentStatusResponse =
            LegalDocumentStatusResponse(
                type = status.document.type,
                version = status.document.version,
                requiredAction = status.document.requiredAction,
                recordedAction = status.recordedAction?.action,
                actedAt = status.recordedAction?.actedAt,
                satisfied = status.satisfied
            )
    }
}

data class RecordLegalDocumentActionRequest(
    val documentType: LegalDocumentType,
    val documentVersion: String,
    val action: LegalDocumentAction
)

data class LegalDocumentActionResponse(
    val id: UUID,
    val documentType: LegalDocumentType,
    val documentVersion: String,
    val action: LegalDocumentAction,
    val actedAt: OffsetDateTime
) {
    companion object {
        fun from(action: UserLegalDocumentAction): LegalDocumentActionResponse =
            LegalDocumentActionResponse(
                id = action.id,
                documentType = action.documentType,
                documentVersion = action.documentVersion,
                action = action.action,
                actedAt = action.actedAt
            )
    }
}
