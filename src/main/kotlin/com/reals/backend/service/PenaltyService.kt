package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PenaltyType
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.service.reputation.TrustScoreEvaluator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.*

@Service
@Transactional
class PenaltyService(
    private val penaltyRepository: PenaltyRepository,
    private val matchmakingQueueRepository: MatchmakingQueueRepository,
    private val trustScoreEvaluator: TrustScoreEvaluator,
    private val auditEventService: AuditEventService
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
        val saved = penaltyRepository.save(penalty)
        auditEventService.record(
            eventType = AuditEventType.PENALTY_APPLIED,
            aggregateType = AuditAggregateType.PENALTY,
            aggregateId = saved.id,
            actorUserId = saved.appliedByUserId,
            targetUserId = saved.userId,
            metadata = mapOf(
                "type" to saved.type.name,
                "sourceReportId" to saved.sourceReportId,
                "expiresAtPresent" to (saved.expiresAt != null)
            )
        )
        return saved
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

    @Transactional(readOnly = true)
    fun findExpiredActivePenalties(
        now: OffsetDateTime = OffsetDateTime.now()
    ): List<Penalty> =
        penaltyRepository.findExpiredActivePenalties(now = now)

    fun expireOverduePenalty(
        penaltyId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean =
        penaltyRepository.deactivateExpiredActivePenalty(
            penaltyId = penaltyId,
            now = now
        ) == 1
}
