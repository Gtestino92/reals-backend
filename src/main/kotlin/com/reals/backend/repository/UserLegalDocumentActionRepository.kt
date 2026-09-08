package com.reals.backend.repository

import com.reals.backend.domain.LegalDocumentType
import com.reals.backend.domain.UserLegalDocumentAction
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserLegalDocumentActionRepository : JpaRepository<UserLegalDocumentAction, UUID> {
    fun findByUserIdAndDocumentTypeAndDocumentVersion(
        userId: UUID,
        documentType: LegalDocumentType,
        documentVersion: String
    ): UserLegalDocumentAction?

    fun findByUserId(userId: UUID): List<UserLegalDocumentAction>
}
