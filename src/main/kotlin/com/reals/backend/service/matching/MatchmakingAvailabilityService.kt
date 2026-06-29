package com.reals.backend.service.matching

import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.PenaltyService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MatchmakingAvailabilityService(
    private val profileRepository: ProfileRepository,
    private val lockRepository: ActiveEngagementLockRepository,
    private val penaltyService: PenaltyService,

    @param:Value("\${engagement.max-active-matches:5}")
    private val maxActiveMatches: Int,

    @param:Value("\${engagement.max-active-connections:2}")
    private val maxActiveConnections: Int
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

        if (penaltyService.hasActivePenalty(userId)) {
            return blocked(
                code = DomainErrorCode.ACTIVE_PENALTY,
                message = "User has an active penalty"
            )
        }

        val activeMatches = lockRepository.countByUserIdAndEngagementType(
            userId = userId,
            engagementType = EngagementType.MATCH
        )

        if (activeMatches >= maxActiveMatches) {
            return blocked(
                code = DomainErrorCode.ACTIVE_MATCH_LIMIT_REACHED,
                message = "User has reached the maximum number of active matches ($maxActiveMatches)"
            )
        }

        val activeConnections = lockRepository.countByUserIdAndEngagementType(
            userId = userId,
            engagementType = EngagementType.CONNECTION
        )

        if (activeConnections >= maxActiveConnections) {
            return blocked(
                code = DomainErrorCode.ACTIVE_CONNECTION_LIMIT_REACHED,
                message = "User has reached the maximum number of active connections ($maxActiveConnections)"
            )
        }

        return MatchmakingAvailability(
            canSearch = true,
            blockedReason = null
        )
    }

    private fun blocked(
        code: DomainErrorCode,
        message: String
    ): MatchmakingAvailability =
        MatchmakingAvailability(
            canSearch = false,
            blockedReason = MatchmakingBlockedReason(
                code = code.name,
                message = message
            )
        )
}
