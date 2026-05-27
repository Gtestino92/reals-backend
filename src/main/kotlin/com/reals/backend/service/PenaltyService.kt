package com.reals.backend.service

import com.reals.backend.domain.Penalty
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.service.reputation.TrustScoreEvaluator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class PenaltyService(
    private val penaltyRepository: PenaltyRepository,
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

    fun createSafetyReportPenalty(
        userId: UUID,
        baseDurationHours: Long = 24
    ): Penalty =
        createPenalty(
            userId = userId,
            reason = "Reported for unsafe chat behavior",
            baseDurationHours = baseDurationHours
        )

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
            expiresAt = OffsetDateTime.now().plusHours(effectiveHours)
        )

        return penaltyRepository.save(penalty)
    }

    /**
     * Deactivates all expired penalties.
     * Called by PenaltyExpirationJob.
     */
    fun expireOverduePenalties() {

        val expired =
            penaltyRepository.findExpiredActivePenalties(
                now = OffsetDateTime.now()
            )

        expired.forEach { it.active = false }

        penaltyRepository.saveAll(expired)
    }

}
