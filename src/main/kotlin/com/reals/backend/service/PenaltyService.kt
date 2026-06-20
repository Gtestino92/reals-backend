package com.reals.backend.service

import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PenaltyType
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.service.reputation.TrustScoreEvaluator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class PenaltyService(
    private val penaltyRepository: PenaltyRepository,
    private val matchmakingQueueRepository: MatchmakingQueueRepository,
    private val trustScoreEvaluator: TrustScoreEvaluator
) {

    fun hasActivePenalty(userId: UUID): Boolean {
        return penaltyRepository.existsByUserIdAndActiveTrue(userId)
    }

    /**
     * Creates a penalty for abandoning the second chat.
     * Base duration is [baseDurationHours]. Effective duration is scaled by the user's
     * trust score: lower score -> longer penalty (progressive enforcement).
     */
    fun createAbandonmentPenalty(
        userId: UUID,
        baseDurationHours: Long = 24
    ): Penalty =
        createPenalty(
            userId = userId,
            reason = "Abandoned second chat",
            baseDurationHours = baseDurationHours
        )

    fun createCancellationPenalty(
        userId: UUID,
        baseDurationHours: Long = 24
    ): Penalty =
        createPenalty(
            userId = userId,
            reason = "Cancelled chat before minimum engagement",
            baseDurationHours = baseDurationHours
        )

    fun createTemporaryPenalty(
        userId: UUID,
        reason: String,
        duration: Duration,
        sourceReportId: UUID? = null,
        appliedByUserId: UUID? = null
    ): Penalty {
        require(!duration.isZero && !duration.isNegative) {
            "Temporary penalty duration must be positive"
        }
        require(reason.isNotBlank()) {
            "Penalty reason is required"
        }

        return savePenalty(
            Penalty(
                userId = userId,
                reason = reason.trim(),
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = OffsetDateTime.now().plus(duration),
                sourceReportId = sourceReportId,
                appliedByUserId = appliedByUserId
            )
        )
    }

    fun createPermanentPenalty(
        userId: UUID,
        reason: String,
        sourceReportId: UUID? = null,
        appliedByUserId: UUID? = null
    ): Penalty {
        require(reason.isNotBlank()) {
            "Penalty reason is required"
        }

        return savePenalty(
            Penalty(
                userId = userId,
                reason = reason.trim(),
                type = PenaltyType.PERMANENT_BAN,
                expiresAt = null,
                sourceReportId = sourceReportId,
                appliedByUserId = appliedByUserId
            )
        )
    }

    private fun createPenalty(
        userId: UUID,
        reason: String,
        baseDurationHours: Long
    ): Penalty {

        val score = trustScoreEvaluator.evaluate(userId)
        val effectiveHours =
            (baseDurationHours * score.penaltyMultiplier()).toLong()

        val penalty = Penalty(
            userId = userId,
            reason = reason,
            type = PenaltyType.TEMPORARY_BAN,
            expiresAt = OffsetDateTime.now().plusHours(effectiveHours)
        )

        return savePenalty(penalty)
    }

    private fun savePenalty(penalty: Penalty): Penalty {
        validatePenaltyShape(penalty)
        matchmakingQueueRepository.deleteByUserId(penalty.userId)
        return penaltyRepository.save(penalty)
    }

    private fun validatePenaltyShape(penalty: Penalty) {
        when (penalty.type) {
            PenaltyType.TEMPORARY_BAN ->
                require(penalty.expiresAt != null) {
                    "Temporary penalty requires expiresAt"
                }

            PenaltyType.PERMANENT_BAN ->
                require(penalty.expiresAt == null) {
                    "Permanent penalty must not have expiresAt"
                }
        }
    }

    /**
     * Deactivates all expired penalties.
     * Called by PenaltyExpirationJob.
     */
    fun expireOverduePenalties(): Int {

        val expired =
            penaltyRepository.findExpiredActivePenalties(
                now = OffsetDateTime.now()
            )

        expired.forEach { it.active = false }

        penaltyRepository.saveAll(expired)

        return expired.size
    }

}
