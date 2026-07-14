package com.reals.backend.domain

import java.util.UUID

data class MatchmakingAnchor(
    val queueEntryId: UUID,
    val userId: UUID
)

data class MatchmakingCandidatePair(
    val userAId: UUID,
    val userBId: UUID,
    val userALatitude: Double,
    val userALongitude: Double,
    val userBLatitude: Double,
    val userBLongitude: Double
)

data class MatchmakingPartnerCandidate(
    val partnerQueueEntryId: UUID,
    val pair: MatchmakingCandidatePair
)
