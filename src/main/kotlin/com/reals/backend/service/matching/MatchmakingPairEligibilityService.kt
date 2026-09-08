package com.reals.backend.service.matching

import com.reals.backend.config.MatchmakingProperties
import com.reals.backend.repository.UserBlockRepository
import com.reals.backend.repository.matching.MatchmakingPairBlockingReason
import com.reals.backend.repository.matching.MatchmakingPairExclusionPolicy
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

    fun firstChatDecisionMismatchCutoff(now: OffsetDateTime): OffsetDateTime =
        now.minusDays(properties.firstChatDecisionMismatchCooldownDays)

    fun isHistoricalExclusionEnabled(): Boolean =
        properties.excludePreviousPairing

    fun effectiveExclusionPolicy(): MatchmakingPairExclusionPolicy =
        MatchmakingPairExclusionPolicy(
            excludeActiveInteractions = !properties.allowActivePairDuplicates,
            excludeHistoricalPairings = properties.excludePreviousPairing
        )

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
            exclusionPolicy = MatchmakingPairExclusionPolicy.ACTIVE_ONLY,
            previousPairingCutoff = null,
            firstChatExpirationCutoff = null,
            firstChatDecisionMismatchCutoff = null
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
            exclusionPolicy = MatchmakingPairExclusionPolicy(
                excludeActiveInteractions = false,
                excludeHistoricalPairings = true
            ),
            previousPairingCutoff = previousPairingCutoff(now),
            firstChatExpirationCutoff = firstChatExpirationCutoff(now),
            firstChatDecisionMismatchCutoff = firstChatDecisionMismatchCutoff(now)
        ) == MatchmakingPairBlockingReason.PREVIOUS_PAIRING_COOLDOWN
    }

    private fun findBlockingReason(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime
    ): MatchmakingPairBlockingReason? {
        val exclusionPolicy = effectiveExclusionPolicy()
        return pairEligibilityRepository.findBlockingReason(
            userAId = userAId,
            userBId = userBId,
            exclusionPolicy = exclusionPolicy,
            previousPairingCutoff =
                if (exclusionPolicy.excludeHistoricalPairings) previousPairingCutoff(now) else null,
            firstChatExpirationCutoff =
                if (exclusionPolicy.excludeHistoricalPairings) firstChatExpirationCutoff(now) else null,
            firstChatDecisionMismatchCutoff =
                if (exclusionPolicy.excludeHistoricalPairings) firstChatDecisionMismatchCutoff(now) else null
        )
    }
}
