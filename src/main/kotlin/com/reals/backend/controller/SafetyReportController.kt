package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.CreateSafetyReportRequest
import com.reals.backend.controller.dto.SafetyReportResponse
import com.reals.backend.service.SafetyReportService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/safety/reports")
class SafetyReportController(
    private val safetyReportService: SafetyReportService
) {

    @PostMapping
    fun createReport(
        @CurrentUserId reporterUserId: UUID,
        @Valid @RequestBody request: CreateSafetyReportRequest
    ): ResponseEntity<SafetyReportResponse> {
        val result = safetyReportService.createUserReport(
            reporterUserId = reporterUserId,
            request = request
        )
        val response = SafetyReportResponse.from(result.report)

        return if (result.created) {
            ResponseEntity.created(URI.create("/api/safety/reports/${result.report.id}"))
                .body(response)
        } else {
            ResponseEntity.ok(response)
        }
    }
}
