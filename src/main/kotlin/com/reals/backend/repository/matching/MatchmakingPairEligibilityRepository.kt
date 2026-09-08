package com.reals.backend.repository.matching

import java.time.OffsetDateTime
import java.util.UUID

enum class MatchmakingPairBlockingReason {
    ACTIVE_INTERACTION,
    PREVIOUS_PAIRING_COOLDOWN
}

data class MatchmakingPairExclusionPolicy(
    val excludeActiveInteractions: Boolean,
    val excludeHistoricalPairings: Boolean
) {
    companion object {
        val ACTIVE_ONLY =
            MatchmakingPairExclusionPolicy(
                excludeActiveInteractions = true,
                excludeHistoricalPairings = false
            )
    }
}

interface MatchmakingPairEligibilityRepository {
    fun findBlockingReason(
        userAId: UUID,
        userBId: UUID,
        exclusionPolicy: MatchmakingPairExclusionPolicy,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?,
        firstChatDecisionMismatchCutoff: OffsetDateTime?
    ): MatchmakingPairBlockingReason?
}
