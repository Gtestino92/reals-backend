package com.reals.backend.service.matching

import com.reals.backend.config.MatchmakingRankingProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime

class ProbabilisticMatchmakingWeightPolicyTest {

    private val now = OffsetDateTime.parse("2026-07-14T12:00:00Z")
    private val policy =
        ProbabilisticMatchmakingWeightPolicy(
            MatchmakingRankingProperties(
                compatibilityTemperature = 0.20,
                reliabilitySimilarityScale = 10.0,
                waitingRelaxationPeriodHours = 72.0,
                maximumSimilarityScaleMultiplier = 3.0
            )
        )

    @Test
    fun `higher compatibility produces higher log weight when reliability and waiting are equal`() {
        val lower = weight(compatibilityScore = 0.8)
        val higher = weight(compatibilityScore = 1.0)

        assertTrue(higher.logWeight > lower.logWeight)
    }

    @Test
    fun `smaller reliability gap produces higher log weight when compatibility and waiting are equal`() {
        val largerGap = weight(anchorReliabilityScore = 120.0, partnerReliabilityScore = 90.0)
        val smallerGap = weight(anchorReliabilityScore = 120.0, partnerReliabilityScore = 110.0)

        assertTrue(smallerGap.logWeight > largerGap.logWeight)
    }

    @Test
    fun `equal reliability scores have same similarity component regardless of absolute level`() {
        val high = weight(anchorReliabilityScore = 120.0, partnerReliabilityScore = 120.0)
        val base = weight(anchorReliabilityScore = 100.0, partnerReliabilityScore = 100.0)
        val low = weight(anchorReliabilityScore = 80.0, partnerReliabilityScore = 80.0)

        assertEquals(high.reliabilityLogWeight, base.reliabilityLogWeight)
        assertEquals(base.reliabilityLogWeight, low.reliabilityLogWeight)
    }

    @Test
    fun `waiting increases effective similarity scale and non-zero-gap weight`() {
        val recent = weight(
            anchorReliabilityScore = 120.0,
            partnerReliabilityScore = 90.0,
            partnerEnteredAt = now
        )
        val older = weight(
            anchorReliabilityScore = 120.0,
            partnerReliabilityScore = 90.0,
            partnerEnteredAt = now.minusHours(72)
        )

        assertTrue(older.effectiveReliabilitySimilarityScale > recent.effectiveReliabilitySimilarityScale)
        assertTrue(older.logWeight > recent.logWeight)
    }

    @Test
    fun `waiting does not change zero-gap reliability component`() {
        val recent = weight(partnerEnteredAt = now)
        val older = weight(partnerEnteredAt = now.minusHours(72))

        assertEquals(recent.reliabilityLogWeight, older.reliabilityLogWeight)
    }

    @Test
    fun `waiting relaxation stops at maximum multiplier`() {
        val old = weight(
            anchorReliabilityScore = 120.0,
            partnerReliabilityScore = 80.0,
            partnerEnteredAt = now.minusHours(10_000)
        )

        assertEquals(3.0, old.waitingMultiplier)
        assertEquals(30.0, old.effectiveReliabilitySimilarityScale)
    }

    @Test
    fun `future entered at timestamps are treated as zero waiting`() {
        val future = weight(partnerEnteredAt = now.plusHours(1))

        assertEquals(0.0, future.waitingHours)
        assertEquals(1.0, future.waitingMultiplier)
    }

    @Test
    fun `non-finite compatibility score is rejected`() {
        assertThrows<IllegalArgumentException> {
            MatchmakingCandidateWeightInput(
                compatibilityScore = Double.NaN,
                anchorReliabilityScore = 100.0,
                partnerReliabilityScore = 100.0,
                partnerEnteredAt = now,
                now = now
            )
        }
    }

    @Test
    fun `compatibility score one contributes zero in log space`() {
        assertEquals(0.0, weight(compatibilityScore = 1.0).compatibilityLogWeight)
    }

    private fun weight(
        compatibilityScore: Double = 1.0,
        anchorReliabilityScore: Double? = 100.0,
        partnerReliabilityScore: Double? = 100.0,
        partnerEnteredAt: OffsetDateTime = now
    ) =
        policy.calculate(
            MatchmakingCandidateWeightInput(
                compatibilityScore = compatibilityScore,
                anchorReliabilityScore = anchorReliabilityScore,
                partnerReliabilityScore = partnerReliabilityScore,
                partnerEnteredAt = partnerEnteredAt,
                now = now
            )
        )
}
