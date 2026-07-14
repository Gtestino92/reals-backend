package com.reals.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

enum class MatchmakingRankingMode {
    LEGACY_EARLY_ACCEPT,
    PROBABILISTIC_WEIGHTED
}

@ConfigurationProperties(prefix = "matchmaking.ranking")
data class MatchmakingRankingProperties(
    val mode: MatchmakingRankingMode = MatchmakingRankingMode.LEGACY_EARLY_ACCEPT,
    val compatibilityTemperature: Double = 0.20,
    val reliabilitySimilarityScale: Double = 10.0,
    val waitingRelaxationPeriodHours: Double = 72.0,
    val maximumSimilarityScaleMultiplier: Double = 3.0
) {
    init {
        require(compatibilityTemperature.isFinite() && compatibilityTemperature > 0.0) {
            "matchmaking.ranking.compatibility-temperature must be finite and greater than 0"
        }
        require(reliabilitySimilarityScale.isFinite() && reliabilitySimilarityScale > 0.0) {
            "matchmaking.ranking.reliability-similarity-scale must be finite and greater than 0"
        }
        require(waitingRelaxationPeriodHours.isFinite() && waitingRelaxationPeriodHours > 0.0) {
            "matchmaking.ranking.waiting-relaxation-period-hours must be finite and greater than 0"
        }
        require(maximumSimilarityScaleMultiplier.isFinite() && maximumSimilarityScaleMultiplier >= 1.0) {
            "matchmaking.ranking.maximum-similarity-scale-multiplier must be finite and at least 1"
        }
    }
}
