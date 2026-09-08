package com.reals.backend.controller.dto

import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.validation.PlainText
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class CreateSafetyReportRequest(
    val reportedUserId: UUID,
    val contextType: SafetyReportContextType,
    val chatId: UUID? = null,
    val matchId: UUID? = null,
    val connectionId: UUID? = null,
    val profilePhotoId: UUID? = null,
    val reason: SafetyReportReason,

    @field:NotBlank
    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val details: String,

    val blockUser: Boolean = false
)

data class SafetyReportResponse(
    val id: UUID,
    val reporterUserId: UUID,
    val reportedUserId: UUID,
    val chatId: UUID?,
    val matchId: UUID?,
    val connectionId: UUID?,
    val contextType: SafetyReportContextType,
    val contextId: UUID?,
    val reason: SafetyReportReason,
    val status: SafetyReportStatus,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun from(report: SafetyReport) =
            SafetyReportResponse(
                id = report.id,
                reporterUserId = requireNotNull(report.reporterUserId) {
                    "User-facing safety report response requires a reporter user id"
                },
                reportedUserId = report.reportedUserId,
                chatId = report.chatId,
                matchId = report.matchId,
                connectionId = report.connectionId,
                contextType = report.contextType,
                contextId = report.contextId,
                reason = report.reason,
                status = report.status,
                createdAt = report.createdAt
            )
    }
}
