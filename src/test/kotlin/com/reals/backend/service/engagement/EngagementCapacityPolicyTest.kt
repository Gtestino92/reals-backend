package com.reals.backend.service.engagement

import com.reals.backend.config.EngagementProperties
import com.reals.backend.config.ReliabilityCapacityCurveProperties
import com.reals.backend.config.ReliabilityCapacityProperties
import com.reals.backend.service.reliability.ReliabilityScoreProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID

class EngagementCapacityPolicyTest {

    @Test
    fun `reliability disabled returns exact configured neutral caps without score lookup`() {
        val provider = FakeReliabilityScoreProvider(
            enabled = false,
            scores = emptyMap()
        )
        val policy = EngagementCapacityPolicy(
            engagementProperties = EngagementProperties(
                maxActiveMatches = 7,
                maxActiveConnections = 5,
                reliabilityCapacity = defaultCurves(matchMax = 9, connectionMax = 6)
            ),
            reliabilityScoreProvider = provider
        )
        val userId = UUID.randomUUID()

        val capacity = policy.capacityFor(userId, fixedNow)

        assertEquals(100.0, capacity.effectiveScore)
        assertEquals(7, capacity.matchCap)
        assertEquals(5, capacity.connectionCap)
        assertEquals(0, provider.lookupCount)
    }

    @Test
    fun `base score returns default neutral caps`() {
        val capacity = enabledPolicy(score = 100.0).capacityForScore(100.0)

        assertEquals(100.0, capacity.effectiveScore)
        assertEquals(5, capacity.matchCap)
        assertEquals(4, capacity.connectionCap)
    }

    @Test
    fun `mild positive delta does not buy excessive capacity`() {
        val capacity = enabledPolicy(score = 105.0).capacityForScore(105.0)

        assertEquals(5, capacity.matchCap)
        assertEquals(4, capacity.connectionCap)
    }

    @Test
    fun `positive ten delta follows asymmetric curve`() {
        val capacity = enabledPolicy(score = 110.0).capacityForScore(110.0)

        assertEquals(6, capacity.matchCap)
        assertEquals(4, capacity.connectionCap)
    }

    @Test
    fun `negative ten delta follows stronger penalty curve`() {
        val capacity = enabledPolicy(score = 90.0).capacityForScore(90.0)

        assertEquals(4, capacity.matchCap)
        assertEquals(3, capacity.connectionCap)
    }

    @Test
    fun `high positive score saturates at maximum caps`() {
        val capacity = enabledPolicy(score = 500.0).capacityForScore(500.0)

        assertEquals(9, capacity.matchCap)
        assertEquals(6, capacity.connectionCap)
    }

    @Test
    fun `low score saturates at minimum caps`() {
        val capacity = enabledPolicy(score = -500.0).capacityForScore(-500.0)

        assertEquals(3, capacity.matchCap)
        assertEquals(2, capacity.connectionCap)
    }

    @Test
    fun `connection six requires extreme positive reliability`() {
        val policy = enabledPolicy(score = 100.0)

        assertEquals(5, policy.capacityForScore(151.0).connectionCap)
        assertEquals(6, policy.capacityForScore(152.0).connectionCap)
    }

    @Test
    fun `match nine requires extreme positive reliability`() {
        val policy = enabledPolicy(score = 100.0)

        assertEquals(8, policy.capacityForScore(152.0).matchCap)
        assertEquals(9, policy.capacityForScore(153.0).matchCap)
    }

    @Test
    fun `temporal score decay can move capacity back toward baseline`() {
        val userId = UUID.randomUUID()
        val beforeDecay = OffsetDateTime.parse("2026-07-14T12:00:00Z")
        val afterDecay = beforeDecay.plusDays(11)
        val provider = object : ReliabilityScoreProvider {
            override val enabled: Boolean = true
            override val baseScore: Int = 100

            override fun effectiveScores(
                userIds: Collection<UUID>,
                now: OffsetDateTime
            ): Map<UUID, Double> =
                userIds.associateWith {
                    if (now == beforeDecay) {
                        120.0
                    } else {
                        110.0
                    }
                }
        }
        val policy = EngagementCapacityPolicy(
            engagementProperties = EngagementProperties(),
            reliabilityScoreProvider = provider
        )

        assertEquals(7, policy.capacityFor(userId, beforeDecay).matchCap)
        assertEquals(6, policy.capacityFor(userId, afterDecay).matchCap)
    }

    @Test
    fun `one explicit now is propagated to one batched score lookup`() {
        val userAId = UUID.randomUUID()
        val userBId = UUID.randomUUID()
        val provider = FakeReliabilityScoreProvider(
            enabled = true,
            scores = mapOf(
                userAId to 90.0,
                userBId to 110.0
            )
        )
        val policy = EngagementCapacityPolicy(
            engagementProperties = EngagementProperties(),
            reliabilityScoreProvider = provider
        )

        val capacities = policy.capacitiesFor(listOf(userAId, userBId), fixedNow)

        assertEquals(4, capacities.getValue(userAId).matchCap)
        assertEquals(6, capacities.getValue(userBId).matchCap)
        assertEquals(1, provider.lookupCount)
        assertEquals(fixedNow, provider.lastNow)
        assertEquals(setOf(userAId, userBId), provider.lastUserIds)
    }

    @Test
    fun `configuration validates curve min max ordering`() {
        val exception = assertThrows<IllegalArgumentException> {
            EngagementProperties(
                maxActiveMatches = 5,
                maxActiveConnections = 4,
                reliabilityCapacity = ReliabilityCapacityProperties(
                    match = ReliabilityCapacityCurveProperties(
                        min = 9,
                        max = 3,
                        rewardScale = 20.0,
                        penaltyScale = 10.0
                    ),
                    connection = ReliabilityCapacityCurveProperties(
                        min = 2,
                        max = 6,
                        rewardScale = 30.0,
                        penaltyScale = 10.0
                    )
                )
            )
        }

        assertTrue(exception.message.orEmpty().contains("greater than or equal to min"))
    }

    @Test
    fun `configuration validates finite positive scales`() {
        val exception = assertThrows<IllegalArgumentException> {
            ReliabilityCapacityCurveProperties(
                min = 3,
                max = 9,
                rewardScale = 0.0,
                penaltyScale = 10.0
            ).validate(
                prefix = "engagement.reliability-capacity.match"
            )
        }

        assertTrue(exception.message.orEmpty().contains("reward-scale"))
    }

    private fun enabledPolicy(score: Double): EngagementCapacityPolicy {
        val userId = UUID.randomUUID()
        return EngagementCapacityPolicy(
            engagementProperties = EngagementProperties(),
            reliabilityScoreProvider = FakeReliabilityScoreProvider(
                enabled = true,
                scores = mapOf(userId to score)
            )
        )
    }

    private fun defaultCurves(
        matchMax: Int,
        connectionMax: Int
    ): ReliabilityCapacityProperties =
        ReliabilityCapacityProperties(
            match = ReliabilityCapacityCurveProperties(
                min = 3,
                max = matchMax,
                rewardScale = 20.0,
                penaltyScale = 10.0
            ),
            connection = ReliabilityCapacityCurveProperties(
                min = 2,
                max = connectionMax,
                rewardScale = 30.0,
                penaltyScale = 10.0
            )
        )

    private class FakeReliabilityScoreProvider(
        override val enabled: Boolean,
        private val scores: Map<UUID, Double>,
        override val baseScore: Int = 100
    ) : ReliabilityScoreProvider {
        var lookupCount: Int = 0
        var lastNow: OffsetDateTime? = null
        var lastUserIds: Set<UUID> = emptySet()

        override fun effectiveScores(
            userIds: Collection<UUID>,
            now: OffsetDateTime
        ): Map<UUID, Double> {
            lookupCount += 1
            lastNow = now
            lastUserIds = userIds.toSet()
            return userIds.associateWith { scores[it] ?: baseScore.toDouble() }
        }
    }

    private companion object {
        val fixedNow: OffsetDateTime = OffsetDateTime.parse("2026-07-14T12:00:00Z")
    }
}
