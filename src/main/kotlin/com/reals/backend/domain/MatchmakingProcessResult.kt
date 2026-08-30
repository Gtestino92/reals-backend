package com.reals.backend.domain

data class MatchmakingProcessResult(
    val candidatePairs: Int,
    val matchesCreated: Int,
    val failedPairs: Int,
    val matches: List<Match>,
    val limitExhausted: Boolean = false
)
