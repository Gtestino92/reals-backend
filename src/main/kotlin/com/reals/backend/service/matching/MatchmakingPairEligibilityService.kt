package com.reals.backend.service.matching

import com.reals.backend.config.MatchmakingProperties
import com.reals.backend.repository.UserBlockRepository
import com.reals.backend.repository.matching.MatchmakingPairBlockingReason
import com.reals.backend.repository.matching.MatchmakingPairEligibilityRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class MatchmakingPairEligibilityService(
    private val properties: MatchmakingProperties,
    private val pairEligibilityRepository: MatchmakingPairEligibilityRepository,
    private val userBlockRepository: UserBlockRepository
) {

    fun previousPairingCutoff(now: OffsetDateTime): OffsetDateTime =
        now.minusDays(properties.previousPairingCooldownDays)

    fun firstChatExpirationCutoff(now: OffsetDateTime): OffsetDateTime =
        now.minusDays(properties.firstChatExpirationCooldownDays)

    fun isHistoricalExclusionEnabled(): Boolean =
        properties.excludePreviousPairing

    fun requirePairCanCreateMatch(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ) {
        when (findBlockingReason(userAId, userBId, now)) {
            MatchmakingPairBlockingReason.ACTIVE_INTERACTION ->
                error("Cannot create match: users already have an active interaction")

            MatchmakingPairBlockingReason.PREVIOUS_PAIRING_COOLDOWN ->
                error("Cannot create match: users are inside previous-pairing cooldown")

            null -> Unit
        }
    }

    fun isPairEligible(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean =
        !userBlockRepository.existsBetweenUsers(userAId, userBId) &&
            findBlockingReason(userAId, userBId, now) == null

    fun hasActiveInteraction(
        userAId: UUID,
        userBId: UUID
    ): Boolean =
        pairEligibilityRepository.findBlockingReason(
            userAId = userAId,
            userBId = userBId,
            previousPairingCutoff = null,
            firstChatExpirationCutoff = null
        ) == MatchmakingPairBlockingReason.ACTIVE_INTERACTION

    fun hasActiveHistoricalCooldown(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean {
        if (!properties.excludePreviousPairing) {
            return false
        }

        return pairEligibilityRepository.findBlockingReason(
            userAId = userAId,
            userBId = userBId,
            previousPairingCutoff = previousPairingCutoff(now),
            firstChatExpirationCutoff = firstChatExpirationCutoff(now)
        ) == MatchmakingPairBlockingReason.PREVIOUS_PAIRING_COOLDOWN
    }

    private fun findBlockingReason(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime
    ): MatchmakingPairBlockingReason? =
        pairEligibilityRepository.findBlockingReason(
            userAId = userAId,
            userBId = userBId,
            previousPairingCutoff =
                if (properties.excludePreviousPairing) previousPairingCutoff(now) else null,
            firstChatExpirationCutoff =
                if (properties.excludePreviousPairing) firstChatExpirationCutoff(now) else null
        )
}
