package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PenaltyType
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.PenaltyRepository
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
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService
) {

    @Transactional(readOnly = true)
    fun hasEffectiveBan(
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean =
        resolveEffectiveBan(userId = userId, now = now) != null

    @Transactional(readOnly = true)
    fun resolveEffectiveBan(
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): EffectiveAccountBan? {
        val effectiveBans = penaltyRepository.findEffectiveBans(
            userId = userId,
            now = now
        )

        if (effectiveBans.any { it.type == PenaltyType.PERMANENT_BAN }) {
            return EffectiveAccountBan(
                type = PenaltyType.PERMANENT_BAN,
                expiresAt = null
            )
        }

        val latestTemporaryExpiry =
            effectiveBans
                .asSequence()
                .filter { it.type == PenaltyType.TEMPORARY_BAN }
                .mapNotNull { it.expiresAt }
                .maxOrNull()

        return latestTemporaryExpiry?.let {
            EffectiveAccountBan(
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = it
            )
        }
    }

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
        homeStateInvalidationService.bump(
            userId = saved.userId,
            reason = "penalty_applied"
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
    ): Boolean {
        val userId = penaltyRepository.findById(penaltyId).orElse(null)?.userId
        val expired = penaltyRepository.deactivateExpiredActivePenalty(
            penaltyId = penaltyId,
            now = now
        ) == 1
        if (expired && userId != null) {
            homeStateInvalidationService.bump(
                userId = userId,
                reason = "penalty_expired"
            )
        }
        return expired
    }
}

data class EffectiveAccountBan(
    val type: PenaltyType,
    val expiresAt: OffsetDateTime?
)
