package com.reals.backend.repository.matching

import java.time.OffsetDateTime
import java.util.UUID

enum class MatchmakingPairBlockingReason {
    ACTIVE_INTERACTION,
    PREVIOUS_PAIRING_COOLDOWN
}

interface MatchmakingPairEligibilityRepository {
    fun findBlockingReason(
        userAId: UUID,
        userBId: UUID,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?
    ): MatchmakingPairBlockingReason?
}
