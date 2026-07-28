package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.CreateAdminSafetyReportRequest
import com.reals.backend.controller.dto.SafetyReportAdminDetail
import com.reals.backend.controller.dto.SafetyReportAdminSummary
import com.reals.backend.controller.dto.SafetyReportDismissRequest
import com.reals.backend.controller.dto.SafetyReportPenaltyRequest
import com.reals.backend.domain.SafetyReportSource
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.service.S3StorageService
import com.reals.backend.service.reports.SafetyReportService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin/safety-reports")
class AdminSafetyReportController(
    private val safetyReportService: SafetyReportService,
    private val storageService: S3StorageService
) {

    @GetMapping("/pending")
    fun listPendingReports(): ResponseEntity<List<SafetyReportAdminSummary>> =
        ResponseEntity.ok(
            safetyReportService.listReportDetails(
                status = SafetyReportStatus.PENDING,
                source = null,
                reportedUserId = null,
                reporterUserId = null
            ).map { SafetyReportAdminSummary.from(it) }
        )

    @GetMapping
    fun listReports(
        @RequestParam(defaultValue = "PENDING") status: SafetyReportStatus?,
        @RequestParam(required = false) source: SafetyReportSource?,
        @RequestParam(required = false) reportedUserId: UUID?,
        @RequestParam(required = false) reporterUserId: UUID?
    ): ResponseEntity<List<SafetyReportAdminSummary>> =
        ResponseEntity.ok(
            safetyReportService.listReportDetails(
                status = status,
                source = source,
                reportedUserId = reportedUserId,
                reporterUserId = reporterUserId
            ).map { SafetyReportAdminSummary.from(it) }
        )

    @PostMapping
    fun createReport(
        @CurrentUserId adminUserId: UUID,
        @Valid
        @RequestBody request: CreateAdminSafetyReportRequest
    ): ResponseEntity<SafetyReportAdminDetail> {
        val report = safetyReportService.createAdminReport(
            adminUserId = adminUserId,
            request = request
        )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(safetyReportAdminDetail(safetyReportService.getReportDetail(report.id)))
    }

    @GetMapping("/{reportId}")
    fun getReport(
        @PathVariable reportId: UUID
    ): ResponseEntity<SafetyReportAdminDetail> =
        ResponseEntity.ok(
            safetyReportAdminDetail(
                safetyReportService.getReportDetail(reportId)
            )
        )

    @PostMapping("/{reportId}/dismissal")
    fun dismissReport(
        @PathVariable reportId: UUID,
        @CurrentUserId adminUserId: UUID,
        @Valid
        @RequestBody request: SafetyReportDismissRequest
    ): ResponseEntity<SafetyReportAdminSummary> =
        ResponseEntity.ok(
            SafetyReportAdminSummary.from(
                safetyReportService.getReportDetail(
                    safetyReportService.dismissReport(
                        reportId = reportId,
                        adminUserId = adminUserId,
                        notes = request.notes
                    ).id
                )
            )
        )

    @PostMapping("/{reportId}/abusive-dismissal")
    fun dismissAbusiveOrUnjustifiedReport(
        @PathVariable reportId: UUID,
        @CurrentUserId adminUserId: UUID,
        @Valid
        @RequestBody request: SafetyReportDismissRequest
    ): ResponseEntity<SafetyReportAdminSummary> =
        ResponseEntity.ok(
            SafetyReportAdminSummary.from(
                safetyReportService.getReportDetail(
                    safetyReportService.dismissAbusiveOrUnjustifiedReport(
                        reportId = reportId,
                        adminUserId = adminUserId,
                        notes = request.notes
                    ).id
                )
            )
        )

    @PostMapping("/{reportId}/penalty")
    fun confirmReportWithPenalty(
        @PathVariable reportId: UUID,
        @CurrentUserId adminUserId: UUID,
        @Valid
        @RequestBody request: SafetyReportPenaltyRequest
    ): ResponseEntity<SafetyReportAdminSummary> =
        ResponseEntity.ok(
            SafetyReportAdminSummary.from(
                safetyReportService.getReportDetail(
                    safetyReportService.confirmReportWithPenalty(
                        reportId = reportId,
                        adminUserId = adminUserId,
                        penaltyType = request.type,
                        durationHours = request.durationHours,
                        reason = request.reason,
                        notes = request.notes
                    ).id
                )
            )
        )

    private fun safetyReportAdminDetail(
        detail: com.reals.backend.service.reports.SafetyReportDetail
    ): SafetyReportAdminDetail =
        SafetyReportAdminDetail.from(detail) { message ->
            storageService.getReadUrl(
                bucket = requireNotNull(message.audioBucket),
                key = requireNotNull(message.audioObjectKey)
            )
        }
}
