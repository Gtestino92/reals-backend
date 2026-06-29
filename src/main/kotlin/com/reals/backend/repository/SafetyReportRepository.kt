package com.reals.backend.repository

import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SafetyReportRepository : JpaRepository<SafetyReport, UUID> {

    fun findByStatusOrderByCreatedAtDesc(status: SafetyReportStatus): List<SafetyReport>

    fun findAllByOrderByCreatedAtDesc(): List<SafetyReport>

    fun findByReporterUserIdAndReportedUserIdAndContextTypeAndContextId(
        reporterUserId: UUID,
        reportedUserId: UUID,
        contextType: SafetyReportContextType,
        contextId: UUID
    ): SafetyReport?
}
