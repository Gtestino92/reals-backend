package com.reals.backend.repository.projection

interface MatchmakingCandidatePairProjection {
    val userAId: String
    val userBId: String
    val userALatitude: Double
    val userALongitude: Double
    val userBLatitude: Double
    val userBLongitude: Double
}
