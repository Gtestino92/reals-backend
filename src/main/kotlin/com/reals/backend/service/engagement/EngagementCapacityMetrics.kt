package com.reals.backend.service.engagement

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import kotlin.math.abs

enum class EngagementCapacityEvaluationPhase(val tagValue: String) {
    AVAILABILITY("availability"),
    FINAL_MATCH_ADMISSION("final_match_admission")
}

enum class EngagementCapacityOutcome(val tagValue: String) {
    ALLOWED("allowed"),
    BLOCKED_MATCH_CAP("blocked_match_cap"),
    BLOCKED_CONNECTION_CAP("blocked_connection_cap")
}

@Component
class EngagementCapacityMetrics(
    private val meterRegistry: MeterRegistry
) {

    fun recordDecision(
        phase: EngagementCapacityEvaluationPhase,
        decision: EngagementCapacityAdmissionDecision
    ) {
        val direction = directionFor(decision.capacity.effectiveScore, decision.reliabilityBaseScore)

        Counter.builder(EVALUATIONS)
            .tag(PHASE, phase.tagValue)
            .tag(DIRECTION, direction)
            .tag(OUTCOME, decision.outcome.tagValue)
            .register(meterRegistry)
            .increment()

        DistributionSummary.builder(EFFECTIVE_MATCH_CAP)
            .tag(PHASE, phase.tagValue)
            .tag(DIRECTION, direction)
            .register(meterRegistry)
            .record(decision.capacity.matchCap.toDouble())

        DistributionSummary.builder(EFFECTIVE_CONNECTION_CAP)
            .tag(PHASE, phase.tagValue)
            .tag(DIRECTION, direction)
            .register(meterRegistry)
            .record(decision.capacity.connectionCap.toDouble())

        DistributionSummary.builder(ABSOLUTE_SCORE_DISTANCE)
            .tag(DIRECTION, direction)
            .register(meterRegistry)
            .record(abs(decision.capacity.effectiveScore - decision.reliabilityBaseScore))
    }

    private fun directionFor(
        effectiveScore: Double,
        baseScore: Int
    ): String =
        when {
            effectiveScore < baseScore -> "below_base"
            effectiveScore > baseScore -> "above_base"
            else -> "neutral"
        }

    companion object {
        const val EVALUATIONS = "reals.engagement.capacity.evaluations"
        const val EFFECTIVE_MATCH_CAP = "reals.engagement.capacity.effective_match_cap"
        const val EFFECTIVE_CONNECTION_CAP = "reals.engagement.capacity.effective_connection_cap"
        const val ABSOLUTE_SCORE_DISTANCE = "reals.engagement.capacity.absolute_score_distance"

        private const val PHASE = "phase"
        private const val DIRECTION = "direction"
        private const val OUTCOME = "outcome"
    }
}
