package com.reals.backend.repository.matching

import com.reals.backend.domain.MatchmakingCandidatePair
import java.time.LocalDate
import java.time.OffsetDateTime

interface MatchmakingCandidateRepository {
    fun findEligibleCandidatePairsForUpdate(
        limit: Int,
        today: LocalDate,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?
    ): List<MatchmakingCandidatePair>
}
