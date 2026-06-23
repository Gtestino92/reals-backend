package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.AdminSafetyReportDetailResponse
import com.reals.backend.controller.dto.AdminSafetyReportResponse
import com.reals.backend.controller.dto.SafetyReportDismissRequest
import com.reals.backend.controller.dto.SafetyReportPenaltyRequest
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.service.SafetyReportService
import jakarta.validation.Valid
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
    private val safetyReportService: SafetyReportService
) {

    @GetMapping("/pending")
    fun listPendingReports(): ResponseEntity<List<AdminSafetyReportResponse>> =
        ResponseEntity.ok(
            safetyReportService.listReports(SafetyReportStatus.PENDING)
                .map { AdminSafetyReportResponse.from(it) }
        )

    @GetMapping
    fun listReports(
        @RequestParam status: SafetyReportStatus
    ): ResponseEntity<List<AdminSafetyReportResponse>> =
        ResponseEntity.ok(
            safetyReportService.listReports(status)
                .map { AdminSafetyReportResponse.from(it) }
        )

    @GetMapping("/{reportId}")
    fun getReport(
        @PathVariable reportId: UUID
    ): ResponseEntity<AdminSafetyReportDetailResponse> =
        ResponseEntity.ok(
            AdminSafetyReportDetailResponse.from(
                safetyReportService.getReportDetail(reportId)
            )
        )

    @PostMapping("/{reportId}/dismissal")
    fun dismissReport(
        @PathVariable reportId: UUID,
        @CurrentUserId adminUserId: UUID,
        @Valid
        @RequestBody request: SafetyReportDismissRequest
    ): ResponseEntity<AdminSafetyReportResponse> =
        ResponseEntity.ok(
            AdminSafetyReportResponse.from(
                safetyReportService.dismissReport(
                    reportId = reportId,
                    adminUserId = adminUserId,
                    notes = request.notes
                )
            )
        )

    @PostMapping("/{reportId}/penalty")
    fun confirmReportWithPenalty(
        @PathVariable reportId: UUID,
        @CurrentUserId adminUserId: UUID,
        @Valid
        @RequestBody request: SafetyReportPenaltyRequest
    ): ResponseEntity<AdminSafetyReportResponse> =
        ResponseEntity.ok(
            AdminSafetyReportResponse.from(
                safetyReportService.confirmReportWithPenalty(
                    reportId = reportId,
                    adminUserId = adminUserId,
                    penaltyType = request.type,
                    durationHours = request.durationHours,
                    reason = request.reason,
                    notes = request.notes
                )
            )
        )
}
