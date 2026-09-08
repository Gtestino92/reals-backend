package com.reals.backend.service.matching

import com.reals.backend.config.MatchmakingAffinityRankingMode
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import kotlin.math.abs

@Component
class MatchmakingAffinityMetrics(
    private val meterRegistry: MeterRegistry
) {

    fun recordEvaluatedWindow(
        mode: MatchmakingAffinityRankingMode,
        diagnostics: List<AffinityCandidateDiagnostics>
    ) {
        if (mode == MatchmakingAffinityRankingMode.OFF) {
            return
        }

        diagnostics.forEach { diagnostic ->
            val assessment = diagnostic.assessment
            val evidence = if (assessment.rankingEligibleSharedQuestionCount > 0) "present" else "none"
            val direction =
                when {
                    assessment.relativeAdjustment > 0.0 -> "positive"
                    assessment.relativeAdjustment < 0.0 -> "negative"
                    else -> "neutral"
                }
            val modeTag = mode.name.lowercase()

            Counter.builder(EVALUATIONS)
                .tag(MODE, modeTag)
                .tag(EVIDENCE, evidence)
                .tag(DIRECTION, direction)
                .register(meterRegistry)
                .increment()

            DistributionSummary.builder(SHARED_QUESTIONS)
                .tag(MODE, modeTag)
                .tag(EVIDENCE, evidence)
                .tag(DIRECTION, direction)
                .register(meterRegistry)
                .record(assessment.sharedValidQuestionCount.toDouble())

            DistributionSummary.builder(EVIDENCE_CONFIDENCE)
                .tag(MODE, modeTag)
                .tag(EVIDENCE, evidence)
                .tag(DIRECTION, direction)
                .register(meterRegistry)
                .record(assessment.evidenceConfidence)

            DistributionSummary.builder(FACTOR)
                .tag(MODE, modeTag)
                .tag(EVIDENCE, evidence)
                .tag(DIRECTION, direction)
                .register(meterRegistry)
                .record(assessment.affinityFactor)

            DistributionSummary.builder(ABSOLUTE_RANK_DELTA)
                .tag(MODE, modeTag)
                .tag(EVIDENCE, evidence)
                .tag(DIRECTION, direction)
                .register(meterRegistry)
                .record(abs(diagnostic.rankDelta).toDouble())
        }
    }

    companion object {
        const val EVALUATIONS = "reals.matchmaking.affinity.evaluations"
        const val SHARED_QUESTIONS = "reals.matchmaking.affinity.shared_questions"
        const val EVIDENCE_CONFIDENCE = "reals.matchmaking.affinity.evidence_confidence"
        const val FACTOR = "reals.matchmaking.affinity.factor"
        const val ABSOLUTE_RANK_DELTA = "reals.matchmaking.affinity.absolute_rank_delta"

        private const val MODE = "mode"
        private const val EVIDENCE = "evidence"
        private const val DIRECTION = "direction"
    }
}

data class AffinityCandidateDiagnostics(
    val assessment: AffinityPairAssessment,
    val baselineDeterministicRank: Int,
    val shadowDeterministicRank: Int,
    val rankDelta: Int
)
