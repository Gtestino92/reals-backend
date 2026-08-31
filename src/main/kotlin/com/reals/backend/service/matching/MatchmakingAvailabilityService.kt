package com.reals.backend.service.matching

import com.reals.backend.domain.ProfileStatus
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.PenaltyService
import com.reals.backend.service.engagement.EngagementCapacityAdmissionService
import com.reals.backend.service.engagement.EngagementCapacityEvaluationPhase
import com.reals.backend.service.engagement.EngagementCapacityOutcome
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class MatchmakingAvailabilityService(
    private val profileRepository: ProfileRepository,
    private val penaltyService: PenaltyService,
    private val visualAdvancementCapService: VisualAdvancementCapService,
    private val engagementCapacityAdmissionService: EngagementCapacityAdmissionService
) {

    @Transactional(readOnly = true)
    fun availabilityFor(
        userId: UUID,
        inQueue: Boolean
    ): MatchmakingAvailability {
        if (inQueue) {
            return MatchmakingAvailability(
                canSearch = false,
                blockedReason = null
            )
        }

        return availabilityForUserNotInQueue(userId)
    }

    @Transactional(readOnly = true)
    fun availabilityForUserNotInQueue(userId: UUID): MatchmakingAvailability {
        return availabilityForUserNotInQueue(
            userId = userId,
            now = OffsetDateTime.now()
        )
    }

    @Transactional(readOnly = true)
    fun availabilityForUserNotInQueue(
        userId: UUID,
        now: OffsetDateTime
    ): MatchmakingAvailability =
        availabilityForUserNotInQueue(
            userId = userId,
            now = now,
            capacityEvaluationPhase = EngagementCapacityEvaluationPhase.AVAILABILITY
        )

    @Transactional(readOnly = true)
    fun availabilityForQueueReconciliation(
        userId: UUID,
        now: OffsetDateTime
    ): MatchmakingAvailability =
        availabilityForUserNotInQueue(
            userId = userId,
            now = now,
            capacityEvaluationPhase = EngagementCapacityEvaluationPhase.QUEUE_RECONCILIATION
        )

    private fun availabilityForUserNotInQueue(
        userId: UUID,
        now: OffsetDateTime,
        capacityEvaluationPhase: EngagementCapacityEvaluationPhase
    ): MatchmakingAvailability {
        val profile = profileRepository.findByUserId(userId)
            ?: return blocked(
                code = DomainErrorCode.PROFILE_REQUIRED,
                message = "User must create a profile before entering matchmaking"
            )

        if (profile.status != ProfileStatus.ACTIVE) {
            return blocked(
                code = DomainErrorCode.PROFILE_NOT_ACTIVE,
                message = "Profile must be active before entering matchmaking"
            )
        }

        if (penaltyService.hasEffectiveBan(userId = userId, now = now)) {
            return blocked(
                code = DomainErrorCode.ACTIVE_PENALTY,
                message = "User has an effective account ban"
            )
        }

        val capacityDecision = engagementCapacityAdmissionService.evaluateUser(
            userId = userId,
            now = now,
            phase = capacityEvaluationPhase
        )

        when (capacityDecision.outcome) {
            EngagementCapacityOutcome.BLOCKED_MATCH_CAP ->
                return blocked(
                    code = DomainErrorCode.ACTIVE_MATCH_LIMIT_REACHED,
                    message = "User has reached the active match capacity"
                )

            EngagementCapacityOutcome.BLOCKED_CONNECTION_CAP ->
                return blocked(
                    code = DomainErrorCode.ACTIVE_CONNECTION_LIMIT_REACHED,
                    message = "User has reached the active connection capacity"
                )

            EngagementCapacityOutcome.ALLOWED -> Unit
        }

        val visualAdvancementStatus = visualAdvancementCapService.statusFor(
            userId = userId,
            now = now
        )

        if (visualAdvancementStatus.blocked) {
            return blocked(
                code = DomainErrorCode.VISUAL_ADVANCEMENT_LIMIT_REACHED,
                message = "User has reached the Visual Review advancement limit",
                nextAvailableAt = visualAdvancementStatus.nextAvailableAt
            )
        }

        return MatchmakingAvailability(
            canSearch = true,
            blockedReason = null
        )
    }

    private fun blocked(
        code: DomainErrorCode,
        message: String,
        nextAvailableAt: OffsetDateTime? = null
    ): MatchmakingAvailability =
        MatchmakingAvailability(
            canSearch = false,
            blockedReason = MatchmakingBlockedReason(
                code = code.name,
                message = message,
                nextAvailableAt = nextAvailableAt
            )
        )
}
