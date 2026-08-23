package com.reals.backend.service.engagement

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.UUID

class EngagementCapacityMetricsTest {

    @Test
    fun `metrics use low cardinality tags and record cap distributions`() {
        val registry = SimpleMeterRegistry()
        val metrics = EngagementCapacityMetrics(registry)

        metrics.recordDecision(
            phase = EngagementCapacityEvaluationPhase.FINAL_MATCH_ADMISSION,
            decision = EngagementCapacityAdmissionDecision(
                userId = UUID.randomUUID(),
                capacity = EffectiveEngagementCapacity(
                    effectiveScore = 90.0,
                    matchCap = 4,
                    connectionCap = 3
                ),
                activeMatches = 4,
                activeConnections = 1,
                reliabilityBaseScore = 100,
                outcome = EngagementCapacityOutcome.BLOCKED_MATCH_CAP
            )
        )

        val counter =
            registry.get(EngagementCapacityMetrics.EVALUATIONS)
                .tag("phase", "final_match_admission")
                .tag("direction", "below_base")
                .tag("outcome", "blocked_match_cap")
                .counter()
        assertEquals(1.0, counter.count())

        assertNotNull(registry.get(EngagementCapacityMetrics.EFFECTIVE_MATCH_CAP).summary())
        assertNotNull(registry.get(EngagementCapacityMetrics.EFFECTIVE_CONNECTION_CAP).summary())
        assertEquals(
            10.0,
            registry.get(EngagementCapacityMetrics.ABSOLUTE_SCORE_DISTANCE)
                .tag("direction", "below_base")
                .summary()
                .totalAmount()
        )
    }

    @Test
    fun `queue reconciliation records a distinct evaluation phase`() {
        val registry = SimpleMeterRegistry()
        val metrics = EngagementCapacityMetrics(registry)

        metrics.recordDecision(
            phase = EngagementCapacityEvaluationPhase.QUEUE_RECONCILIATION,
            decision = EngagementCapacityAdmissionDecision(
                userId = UUID.randomUUID(),
                capacity = EffectiveEngagementCapacity(
                    effectiveScore = 100.0,
                    matchCap = 5,
                    connectionCap = 4
                ),
                activeMatches = 1,
                activeConnections = 1,
                reliabilityBaseScore = 100,
                outcome = EngagementCapacityOutcome.ALLOWED
            )
        )

        assertEquals(
            1.0,
            registry.get(EngagementCapacityMetrics.EVALUATIONS)
                .tag("phase", "queue_reconciliation")
                .tag("direction", "neutral")
                .tag("outcome", "allowed")
                .counter()
                .count()
        )
    }
}
