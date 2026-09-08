package com.reals.backend.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class VisualReviewAvailabilityPolicyTest {

    @Test
    fun `reliability disabled uses base plus common jitter only`() {
        assertEquals(Duration.ofMinutes(5), policy(0.0).sampleDelay(null))
        assertEquals(Duration.ofMinutes(10), policy(0.5).sampleDelay(null))
        assertEquals(Duration.ofMinutes(15), policy(1.0).sampleDelay(null))
    }

    @Test
    fun `score 100 is centered on ten minutes`() {
        assertEquals(Duration.ofMinutes(5), policy(0.0).sampleDelay(100.0))
        assertEquals(Duration.ofMinutes(10), policy(0.5).sampleDelay(100.0))
        assertEquals(Duration.ofMinutes(15), policy(1.0).sampleDelay(100.0))
    }

    @Test
    fun `high score shifts distribution earlier without changing width`() {
        assertEquals(Duration.ofMinutes(1), policy(0.0).sampleDelay(120.0))
        assertEquals(Duration.ofMinutes(6), policy(0.5).sampleDelay(120.0))
        assertEquals(Duration.ofMinutes(11), policy(1.0).sampleDelay(120.0))
    }

    @Test
    fun `low score shifts distribution later without changing width`() {
        assertEquals(Duration.ofMinutes(9), policy(0.0).sampleDelay(80.0))
        assertEquals(Duration.ofMinutes(14), policy(0.5).sampleDelay(80.0))
        assertEquals(Duration.ofMinutes(19), policy(1.0).sampleDelay(80.0))
    }

    @Test
    fun `scores beyond twenty points saturate reliability shift`() {
        assertEquals(Duration.ofMinutes(1), policy(0.0).sampleDelay(140.0))
        assertEquals(Duration.ofMinutes(11), policy(1.0).sampleDelay(140.0))
        assertEquals(Duration.ofMinutes(9), policy(0.0).sampleDelay(60.0))
        assertEquals(Duration.ofMinutes(19), policy(1.0).sampleDelay(60.0))
    }

    @Test
    fun `pair score uses arithmetic mean`() {
        val userAScore = 120.0
        val userBScore = 80.0
        val pairScore = (userAScore + userBScore) / 2.0

        assertEquals(Duration.ofMinutes(10), policy(0.5).sampleDelay(pairScore))
    }

    @Test
    fun `distribution width remains identical regardless of score`() {
        val lowWidth = policy(1.0).sampleDelay(80.0) - policy(0.0).sampleDelay(80.0)
        val baseWidth = policy(1.0).sampleDelay(100.0) - policy(0.0).sampleDelay(100.0)
        val highWidth = policy(1.0).sampleDelay(120.0) - policy(0.0).sampleDelay(120.0)

        assertEquals(Duration.ofMinutes(10), lowWidth)
        assertEquals(baseWidth, lowWidth)
        assertEquals(baseWidth, highWidth)
    }

    @Test
    fun `invalid configuration is rejected`() {
        assertThrows<IllegalArgumentException> {
            policy(random = 0.5, baseDelayMinutes = 9)
        }
        assertThrows<IllegalArgumentException> {
            policy(random = 0.5, jitterRangeMinutes = -1)
        }
        assertThrows<IllegalArgumentException> {
            policy(random = 0.5, maxReliabilityShiftMinutes = -1)
        }
        assertThrows<IllegalArgumentException> {
            policy(random = 0.5, reliabilityInfluenceSpanPoints = 0.0)
        }
    }

    private fun policy(
        random: Double,
        baseDelayMinutes: Long = 10,
        jitterRangeMinutes: Long = 5,
        maxReliabilityShiftMinutes: Long = 4,
        reliabilityInfluenceSpanPoints: Double = 20.0
    ): VisualReviewAvailabilityPolicy =
        VisualReviewAvailabilityPolicy(
            randomSource = VisualReviewAvailabilityRandomSource { random },
            baseReliabilityScore = 100.0,
            baseDelayMinutes = baseDelayMinutes,
            jitterRangeMinutes = jitterRangeMinutes,
            maxReliabilityShiftMinutes = maxReliabilityShiftMinutes,
            reliabilityInfluenceSpanPoints = reliabilityInfluenceSpanPoints
        )
}
