package com.reals.backend.controller.dto

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PenaltyType
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportEvidenceSnapshot
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.SafetyReportSource
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.domain.User
import com.reals.backend.domain.UserStatus
import com.reals.backend.domain.priorityReview
import com.reals.backend.service.reports.SafetyReportDetail
import com.reals.backend.service.reports.SafetyReportUserCounters
import com.reals.backend.validation.PlainText
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class CreateAdminSafetyReportRequest(
    val reportedUserId: UUID,
    val reporterUserId: UUID? = null,
    val contextType: SafetyReportContextType = SafetyReportContextType.USER,
    val chatId: UUID? = null,
    val matchId: UUID? = null,
    val connectionId: UUID? = null,
    val profilePhotoId: UUID? = null,
    val reason: SafetyReportReason,

    @field:NotBlank
    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val details: String
)

data class SafetyUserAdminSummary(
    val userId: UUID,
    val status: UserStatus,
    val createdAt: OffsetDateTime?,
    val deletedAt: OffsetDateTime?,
    val emailMasked: String? = null
) {
    companion object {
        fun from(user: User?): SafetyUserAdminSummary? =
            user?.let {
                SafetyUserAdminSummary(
                    userId = it.id,
                    status = it.status,
                    createdAt = it.createdAt,
                    deletedAt = it.deletedAt,
                    emailMasked = null
                )
            }
    }
}

data class SafetyReportAdminSummary(
    val id: UUID,
    val source: SafetyReportSource,
    val reporter: SafetyUserAdminSummary?,
    val reported: SafetyUserAdminSummary,
    val contextType: SafetyReportContextType,
    val contextId: UUID?,
    val chatId: UUID?,
    val matchId: UUID?,
    val connectionId: UUID?,
    val reason: SafetyReportReason,
    val status: SafetyReportStatus,
    val priorityReview: Boolean,
    val createdAt: OffsetDateTime,
    val reviewedAt: OffsetDateTime?,
    val createdByAdminUserId: UUID?,
    val reviewedByUserId: UUID?,
    val penaltyId: UUID?,
    val reportedUserCounters: SafetyReportUserCounters
) {
    companion object {
        fun from(detail: SafetyReportDetail): SafetyReportAdminSummary =
            SafetyReportAdminSummary(
                id = detail.report.id,
                source = detail.report.source,
                reporter = SafetyUserAdminSummary.from(detail.reporter),
                reported = SafetyUserAdminSummary.from(detail.reported)
                    ?: error("Reported user ${detail.report.reportedUserId} was not found"),
                contextType = detail.report.contextType,
                contextId = detail.report.contextId,
                chatId = detail.report.chatId,
                matchId = detail.report.matchId,
                connectionId = detail.report.connectionId,
                reason = detail.report.reason,
                status = detail.report.status,
                priorityReview = detail.report.priorityReview,
                createdAt = detail.report.createdAt,
                reviewedAt = detail.report.reviewedAt,
                createdByAdminUserId = detail.report.createdByAdminUserId,
                reviewedByUserId = detail.report.reviewedByUserId,
                penaltyId = detail.report.penaltyId,
                reportedUserCounters = detail.reportedUserCounters
            )
    }
}

data class SafetyReportAdminDetail(
    val report: SafetyReportAdminSummary,
    val details: String,
    val verdictNotes: String?,
    val evidence: SafetyReportEvidenceSnapshotResponse?,
    val messages: List<SafetyReportMessageEvidenceResponse> = emptyList(),
    val penalty: SafetyPenaltyAdminSummary? = null
) {
    companion object {
        fun from(detail: SafetyReportDetail): SafetyReportAdminDetail =
            SafetyReportAdminDetail(
                report = SafetyReportAdminSummary.from(detail),
                details = detail.report.details,
                verdictNotes = detail.report.verdictNotes,
                evidence = SafetyReportEvidenceSnapshotResponse.from(detail.evidence),
                messages = detail.messages.map { SafetyReportMessageEvidenceResponse.from(it) },
                penalty = SafetyPenaltyAdminSummary.from(detail.penalty)
            )
    }
}

data class SafetyReportMessageEvidenceResponse(
    val id: UUID,
    val senderUserId: UUID,
    val sentAt: OffsetDateTime,
    val content: String
) {
    companion object {
        fun from(message: ChatMessage) =
            SafetyReportMessageEvidenceResponse(
                id = message.id,
                senderUserId = message.senderId,
                sentAt = message.sentAt,
                content = message.content
            )
    }
}

data class SafetyPenaltyAdminSummary(
    val id: UUID,
    val userId: UUID,
    val type: PenaltyType,
    val active: Boolean,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime?,
    val sourceReportId: UUID?
) {
    companion object {
        fun from(penalty: Penalty?) =
            penalty?.let {
                SafetyPenaltyAdminSummary(
                    id = it.id,
                    userId = it.userId,
                    type = it.type,
                    active = it.active,
                    createdAt = it.createdAt,
                    expiresAt = it.expiresAt,
                    sourceReportId = it.sourceReportId
                )
            }
    }
}

data class SafetyReportEvidenceSnapshotResponse(
    val id: UUID,
    val safetyReportId: UUID,
    val chatId: UUID?,
    val matchId: UUID?,
    val connectionId: UUID?,
    val messageCount: Int,
    val firstMessageAt: OffsetDateTime?,
    val lastMessageAt: OffsetDateTime?,
    val transcriptSha256: String?,
    val capturedAt: OffsetDateTime
) {
    companion object {
        fun from(evidence: SafetyReportEvidenceSnapshot?) =
            evidence?.let {
                SafetyReportEvidenceSnapshotResponse(
                    id = it.id,
                    safetyReportId = it.safetyReportId,
                    chatId = it.chatId,
                    matchId = it.matchId,
                    connectionId = it.connectionId,
                    messageCount = it.messageCount,
                    firstMessageAt = it.firstMessageAt,
                    lastMessageAt = it.lastMessageAt,
                    transcriptSha256 = it.transcriptSha256,
                    capturedAt = it.capturedAt
                )
            }
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
