package com.reals.backend.repository.matching

import com.reals.backend.domain.MatchmakingAnchor
import com.reals.backend.domain.MatchmakingPartnerCandidate
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

interface MatchmakingCandidateRepository {
    fun claimNextEligibleAnchorForUpdate(
        today: LocalDate,
        exclusionPolicy: MatchmakingPairExclusionPolicy,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?,
        firstChatDecisionMismatchCutoff: OffsetDateTime?
    ): MatchmakingAnchor?

    fun findEligiblePartnerCandidates(
        anchorQueueEntryId: UUID,
        limit: Int,
        today: LocalDate,
        exclusionPolicy: MatchmakingPairExclusionPolicy,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?,
        firstChatDecisionMismatchCutoff: OffsetDateTime?
    ): List<MatchmakingPartnerCandidate>

    fun tryClaimEligiblePartnerForUpdate(
        anchorQueueEntryId: UUID,
        partnerQueueEntryId: UUID,
        today: LocalDate,
        exclusionPolicy: MatchmakingPairExclusionPolicy,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?,
        firstChatDecisionMismatchCutoff: OffsetDateTime?
    ): MatchmakingPartnerCandidate?
}
