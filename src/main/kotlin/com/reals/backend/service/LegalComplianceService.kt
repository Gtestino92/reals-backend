package com.reals.backend.service

import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class LegalComplianceService(
    private val legalDocumentService: LegalDocumentService
) {

    fun requireCurrentRequirementsSatisfied(userId: UUID) {
        val status = legalDocumentService.getStatus(userId = userId)

        if (!status.requirementsSatisfied) {
            throw DomainConflictException(
                code = DomainErrorCode.LEGAL_ACTION_REQUIRED,
                message = "Current legal document actions are required before continuing"
            )
        }
    }
}
