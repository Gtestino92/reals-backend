package com.reals.backend.service.matching

import com.reals.backend.config.MatchmakingRankingProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MatchmakingCandidateWeightInput(
    val compatibilityScore: Double,
    val anchorReliabilityScore: Double?,
    val partnerReliabilityScore: Double?,
    val partnerEnteredAt: OffsetDateTime,
    val now: OffsetDateTime
) {
    init {
        require(compatibilityScore.isFinite()) {
            "Compatibility score must be finite"
        }
        require(compatibilityScore in 0.0..1.0) {
            "Compatibility score must be between 0 and 1"
        }
    }
}

data class MatchmakingCandidateWeight(
    val compatibilityLogWeight: Double,
    val reliabilityLogWeight: Double,
    val waitingHours: Double,
    val waitingMultiplier: Double,
    val effectiveReliabilitySimilarityScale: Double,
    val logWeight: Double
)

@Component
class ProbabilisticMatchmakingWeightPolicy(
    private val properties: MatchmakingRankingProperties
) {

    fun calculate(input: MatchmakingCandidateWeightInput): MatchmakingCandidateWeight {
        val compatibilityLogWeight =
            (input.compatibilityScore - 1.0) / properties.compatibilityTemperature

        val waitingHours =
            max(
                0.0,
                Duration.between(input.partnerEnteredAt, input.now).toMillis() / MILLIS_PER_HOUR
            )
        val waitingMultiplier =
            min(
                properties.maximumSimilarityScaleMultiplier,
                1.0 + waitingHours / properties.waitingRelaxationPeriodHours
            )
        val effectiveReliabilitySimilarityScale =
            properties.reliabilitySimilarityScale * waitingMultiplier
        val reliabilityLogWeight =
            if (input.anchorReliabilityScore != null && input.partnerReliabilityScore != null) {
                -abs(input.anchorReliabilityScore - input.partnerReliabilityScore) /
                    effectiveReliabilitySimilarityScale
            } else {
                0.0
            }

        return MatchmakingCandidateWeight(
            compatibilityLogWeight = compatibilityLogWeight,
            reliabilityLogWeight = reliabilityLogWeight,
            waitingHours = waitingHours,
            waitingMultiplier = waitingMultiplier,
            effectiveReliabilitySimilarityScale = effectiveReliabilitySimilarityScale,
            logWeight = compatibilityLogWeight + reliabilityLogWeight
        )
    }

    private companion object {
        const val MILLIS_PER_HOUR = 3_600_000.0
    }
}
