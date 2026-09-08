package com.reals.backend.service.engagement

import com.reals.backend.config.EngagementProperties
import com.reals.backend.config.ReliabilityCapacityCurveProperties
import com.reals.backend.service.reliability.ReliabilityScoreProvider
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

data class EffectiveEngagementCapacity(
    val effectiveScore: Double,
    val matchCap: Int,
    val connectionCap: Int
)

@Component
class EngagementCapacityPolicy(
    private val engagementProperties: EngagementProperties,
    private val reliabilityScoreProvider: ReliabilityScoreProvider
) {

    fun capacityFor(
        userId: UUID,
        now: OffsetDateTime
    ): EffectiveEngagementCapacity =
        capacitiesFor(
            userIds = listOf(userId),
            now = now
        ).getValue(userId)

    fun capacitiesFor(
        userIds: Collection<UUID>,
        now: OffsetDateTime
    ): Map<UUID, EffectiveEngagementCapacity> {
        if (userIds.isEmpty()) {
            return emptyMap()
        }

        val distinctUserIds = userIds.distinct()
        val scores =
            if (reliabilityScoreProvider.enabled) {
                reliabilityScoreProvider.effectiveScores(
                    userIds = distinctUserIds,
                    now = now
                )
            } else {
                distinctUserIds.associateWith { reliabilityScoreProvider.baseScore.toDouble() }
            }

        return distinctUserIds.associateWith { userId ->
            capacityForScore(
                effectiveScore = scores[userId] ?: reliabilityScoreProvider.baseScore.toDouble()
            )
        }
    }

    fun capacityForScore(effectiveScore: Double): EffectiveEngagementCapacity =
        EffectiveEngagementCapacity(
            effectiveScore = effectiveScore,
            matchCap = capFor(
                effectiveScore = effectiveScore,
                baseline = engagementProperties.maxActiveMatches,
                curve = engagementProperties.reliabilityCapacity.match
            ),
            connectionCap = capFor(
                effectiveScore = effectiveScore,
                baseline = engagementProperties.maxActiveConnections,
                curve = engagementProperties.reliabilityCapacity.connection
            )
        )

    private fun capFor(
        effectiveScore: Double,
        baseline: Int,
        curve: ReliabilityCapacityCurveProperties
    ): Int {
        if (!reliabilityScoreProvider.enabled) {
            return baseline
        }

        val delta = effectiveScore - reliabilityScoreProvider.baseScore
        val raw =
            if (delta >= 0.0) {
                baseline + (curve.max - baseline) * saturating(delta, curve.rewardScale)
            } else {
                baseline - (baseline - curve.min) * saturating(abs(delta), curve.penaltyScale)
            }

        return raw.roundToInt().coerceIn(curve.min, curve.max)
    }

    private fun saturating(
        value: Double,
        scale: Double
    ): Double {
        val squared = value * value
        val scaleSquared = scale * scale
        return squared / (squared + scaleSquared)
    }
}
