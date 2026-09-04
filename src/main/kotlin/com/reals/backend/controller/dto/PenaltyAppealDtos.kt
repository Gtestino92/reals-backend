package com.reals.backend.controller.dto

import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PenaltyAppealDecision
import com.reals.backend.domain.PenaltyAppealStatus
import com.reals.backend.validation.PlainText
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

enum class PublicPenaltyAppealStatus {
    AVAILABLE,
    PENDING,
    APPROVED,
    REJECTED
}

data class MyPenaltyAppealResponse(
    val status: PublicPenaltyAppealStatus,
    val banActive: Boolean,
    val appealedAt: OffsetDateTime?,
    val reviewedAt: OffsetDateTime?
) {
    companion object {
        fun from(penalty: Penalty): MyPenaltyAppealResponse =
            MyPenaltyAppealResponse(
                status = when (penalty.appealStatus) {
                    null -> PublicPenaltyAppealStatus.AVAILABLE
                    PenaltyAppealStatus.PENDING -> PublicPenaltyAppealStatus.PENDING
                    PenaltyAppealStatus.APPROVED -> PublicPenaltyAppealStatus.APPROVED
                    PenaltyAppealStatus.REJECTED -> PublicPenaltyAppealStatus.REJECTED
                },
                banActive = penalty.active,
                appealedAt = penalty.appealedAt,
                reviewedAt = penalty.appealReviewedAt
            )
    }
}

data class SubmitPenaltyAppealRequest(
    @field:NotBlank
    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val statement: String
)

data class AdminPendingPenaltyAppealResponse(
    val penaltyId: UUID,
    val userId: UUID,
    val penaltyReason: String,
    val sourceReportId: UUID?,
    val appealStatement: String,
    val appealedAt: OffsetDateTime,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun from(penalty: Penalty): AdminPendingPenaltyAppealResponse =
            AdminPendingPenaltyAppealResponse(
                penaltyId = penalty.id,
                userId = penalty.userId,
                penaltyReason = penalty.reason,
                sourceReportId = penalty.sourceReportId,
                appealStatement = requireNotNull(penalty.appealStatement),
                appealedAt = requireNotNull(penalty.appealedAt),
                createdAt = penalty.createdAt
            )
    }
}


data class DecidePenaltyAppealRequest(
    val decision: PenaltyAppealDecision,
    @field:NotBlank
    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val notes: String
)
