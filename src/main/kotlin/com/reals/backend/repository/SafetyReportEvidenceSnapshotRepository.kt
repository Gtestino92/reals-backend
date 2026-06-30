package com.reals.backend.repository

import com.reals.backend.domain.SafetyReportEvidenceSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SafetyReportEvidenceSnapshotRepository : JpaRepository<SafetyReportEvidenceSnapshot, UUID> {
    fun findBySafetyReportId(safetyReportId: UUID): SafetyReportEvidenceSnapshot?
}
