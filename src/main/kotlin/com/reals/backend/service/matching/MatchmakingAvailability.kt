package com.reals.backend.service.matching

data class MatchmakingAvailability(
    val canSearch: Boolean,
    val blockedReason: MatchmakingBlockedReason?
)

data class MatchmakingBlockedReason(
    val code: String,
    val message: String
)
