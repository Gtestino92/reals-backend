package com.reals.backend.domain

import java.util.UUID

data class MatchmakingCandidatePair(
    val userAId: UUID,
    val userBId: UUID,
    val userALatitude: Double,
    val userALongitude: Double,
    val userBLatitude: Double,
    val userBLongitude: Double
)

data class ScoredMatchmakingCandidatePair(
    val pair: MatchmakingCandidatePair,
    val score: Double,
    val order: Int
)
