package com.reals.backend.service.matching

import com.reals.backend.config.MatchmakingAffinityRankingMode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class MatchmakingAffinityMetricsTest {

    @Test
    fun `metrics use low cardinality tags and non negative observations`() {
        val registry = SimpleMeterRegistry()
        val metrics = MatchmakingAffinityMetrics(registry)

        metrics.recordEvaluatedWindow(
            mode = MatchmakingAffinityRankingMode.SHADOW,
            diagnostics = listOf(
                AffinityCandidateDiagnostics(
                    assessment =
                        AffinityPairAssessment(
                            sharedValidQuestionCount = 3,
                            rankingEligibleSharedQuestionCount = 3,
                            categoriesWithRankingEvidence = 1,
                            categoryAssessments = emptyList(),
                            overallAffinity = -0.5,
                            evidenceConfidence = 0.25,
                            relativeAdjustment = -0.0125,
                            affinityFactor = 0.9875,
                            affinityLogWeight = kotlin.math.ln(0.9875)
                        ),
                    baselineDeterministicRank = 1,
                    shadowDeterministicRank = 2,
                    rankDelta = 1
                )
            )
        )

        val counter =
            registry.get(MatchmakingAffinityMetrics.EVALUATIONS)
                .tag("mode", "shadow")
                .tag("evidence", "present")
                .tag("direction", "negative")
                .counter()
        assertEquals(1.0, counter.count())

        assertNotNull(registry.get(MatchmakingAffinityMetrics.SHARED_QUESTIONS).summary())
        assertNotNull(registry.get(MatchmakingAffinityMetrics.EVIDENCE_CONFIDENCE).summary())
        assertNotNull(registry.get(MatchmakingAffinityMetrics.FACTOR).summary())
        assertNotNull(registry.get(MatchmakingAffinityMetrics.ABSOLUTE_RANK_DELTA).summary())
    }
}
