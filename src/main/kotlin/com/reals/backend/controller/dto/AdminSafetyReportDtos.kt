package com.reals.backend.controller.dto

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PenaltyType
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.domain.User
import com.reals.backend.domain.UserStatus
import com.reals.backend.service.SafetyReportDetail
import com.reals.backend.validation.PlainText
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class AdminSafetyReportResponse(
    val id: UUID,
    val reporterUserId: UUID,
    val reportedUserId: UUID,
    val chatId: UUID,
    val matchId: UUID,
    val connectionId: UUID?,
    val reason: SafetyReportReason,
    val details: String,
    val status: SafetyReportStatus,
    val createdAt: OffsetDateTime,
    val reviewedAt: OffsetDateTime?,
    val reviewedByUserId: UUID?,
    val verdictNotes: String?,
    val penaltyId: UUID?
) {
    companion object {
        fun from(report: SafetyReport) =
            AdminSafetyReportResponse(
                id = report.id,
                reporterUserId = report.reporterUserId,
                reportedUserId = report.reportedUserId,
                chatId = report.chatId,
                matchId = report.matchId,
                connectionId = report.connectionId,
                reason = report.reason,
                details = report.details,
                status = report.status,
                createdAt = report.createdAt,
                reviewedAt = report.reviewedAt,
                reviewedByUserId = report.reviewedByUserId,
                verdictNotes = report.verdictNotes,
                penaltyId = report.penaltyId
            )
    }
}

data class AdminUserSummaryResponse(
    val id: UUID,
    val email: String?,
    val status: UserStatus
) {
    companion object {
        fun from(user: User?) =
            user?.let {
                AdminUserSummaryResponse(
                    id = it.id,
                    email = it.email,
                    status = it.status
                )
            }
    }
}

data class AdminPenaltyResponse(
    val id: UUID,
    val userId: UUID,
    val reason: String,
    val type: PenaltyType,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime?,
    val sourceReportId: UUID?,
    val appliedByUserId: UUID?,
    val active: Boolean
) {
    companion object {
        fun from(penalty: Penalty?) =
            penalty?.let {
                AdminPenaltyResponse(
                    id = it.id,
                    userId = it.userId,
                    reason = it.reason,
                    type = it.type,
                    createdAt = it.createdAt,
                    expiresAt = it.expiresAt,
                    sourceReportId = it.sourceReportId,
                    appliedByUserId = it.appliedByUserId,
                    active = it.active
                )
            }
    }
}

data class AdminChatMessageResponse(
    val id: UUID,
    val chatSessionId: UUID,
    val senderId: UUID,
    val content: String,
    val sentAt: OffsetDateTime
) {
    companion object {
        fun from(message: ChatMessage) =
            AdminChatMessageResponse(
                id = message.id,
                chatSessionId = message.chatSessionId,
                senderId = message.senderId,
                content = message.content,
                sentAt = message.sentAt
            )
    }
}

data class AdminSafetyReportDetailResponse(
    val report: AdminSafetyReportResponse,
    val reporter: AdminUserSummaryResponse?,
    val reported: AdminUserSummaryResponse?,
    val messages: List<AdminChatMessageResponse>,
    val penalty: AdminPenaltyResponse?
) {
    companion object {
        fun from(detail: SafetyReportDetail) =
            AdminSafetyReportDetailResponse(
                report = AdminSafetyReportResponse.from(detail.report),
                reporter = AdminUserSummaryResponse.from(detail.reporter),
                reported = AdminUserSummaryResponse.from(detail.reported),
                messages = detail.messages.map { AdminChatMessageResponse.from(it) },
                penalty = AdminPenaltyResponse.from(detail.penalty)
            )
    }
}

data class SafetyReportDismissRequest(
    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val notes: String? = null
)

data class SafetyReportPenaltyRequest(
    val type: PenaltyType,

    @field:Positive
    val durationHours: Long? = null,

    @field:NotBlank
    @field:Size(max = 255)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val reason: String,

    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val notes: String? = null
)
