package com.reals.backend.service.matching

import java.time.OffsetDateTime

data class MatchmakingAvailability(
    val canSearch: Boolean,
    val blockedReason: MatchmakingBlockedReason?
)

data class MatchmakingBlockedReason(
    val code: String,
    val message: String,
    val nextAvailableAt: OffsetDateTime? = null
)
