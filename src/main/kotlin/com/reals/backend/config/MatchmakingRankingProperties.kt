package com.reals.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

enum class MatchmakingRankingMode {
    LEGACY_EARLY_ACCEPT,
    PROBABILISTIC_WEIGHTED
}

enum class MatchmakingAffinityRankingMode {
    OFF,
    SHADOW,
    ACTIVE
}

@ConfigurationProperties(prefix = "matchmaking.ranking")
data class MatchmakingRankingProperties(
    val mode: MatchmakingRankingMode = MatchmakingRankingMode.LEGACY_EARLY_ACCEPT,
    val compatibilityTemperature: Double = 0.20,
    val reliabilitySimilarityScale: Double = 10.0,
    val waitingRelaxationPeriodHours: Double = 72.0,
    val maximumSimilarityScaleMultiplier: Double = 3.0,
    val affinity: MatchmakingAffinityRankingProperties = MatchmakingAffinityRankingProperties()
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
        require(mode != MatchmakingRankingMode.LEGACY_EARLY_ACCEPT || affinity.mode != MatchmakingAffinityRankingMode.ACTIVE) {
            "matchmaking.ranking.affinity.mode ACTIVE cannot be used with LEGACY_EARLY_ACCEPT"
        }
    }
}

data class MatchmakingAffinityRankingProperties(
    val mode: MatchmakingAffinityRankingMode = MatchmakingAffinityRankingMode.OFF,
    val maxRelativeAdjustment: Double = 0.10,
    val fullConfidenceSharedQuestions: Int = 12,
    val fullConfidenceCategories: Int = 4,
    val categoryFullConfidenceQuestions: Int = 3
) {
    init {
        require(maxRelativeAdjustment.isFinite() && maxRelativeAdjustment in 0.0..0.25) {
            "matchmaking.ranking.affinity.max-relative-adjustment must be finite and between 0.0 and 0.25"
        }
        require(fullConfidenceSharedQuestions > 0) {
            "matchmaking.ranking.affinity.full-confidence-shared-questions must be positive"
        }
        require(fullConfidenceCategories > 0) {
            "matchmaking.ranking.affinity.full-confidence-categories must be positive"
        }
        require(categoryFullConfidenceQuestions > 0) {
            "matchmaking.ranking.affinity.category-full-confidence-questions must be positive"
        }
    }
}
