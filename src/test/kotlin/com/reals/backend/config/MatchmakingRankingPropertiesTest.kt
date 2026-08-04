package com.reals.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MatchmakingRankingPropertiesTest {

    @Test
    fun `affinity defaults are off and bounded`() {
        val properties = MatchmakingRankingProperties()

        assertEquals(MatchmakingAffinityRankingMode.OFF, properties.affinity.mode)
        assertEquals(0.10, properties.affinity.maxRelativeAdjustment)
        assertEquals(12, properties.affinity.fullConfidenceSharedQuestions)
        assertEquals(4, properties.affinity.fullConfidenceCategories)
        assertEquals(3, properties.affinity.categoryFullConfidenceQuestions)
    }

    @Test
    fun `active affinity is rejected with legacy ranking`() {
        assertThrows<IllegalArgumentException> {
            MatchmakingRankingProperties(
                mode = MatchmakingRankingMode.LEGACY_EARLY_ACCEPT,
                affinity = MatchmakingAffinityRankingProperties(mode = MatchmakingAffinityRankingMode.ACTIVE)
            )
        }
    }

    @Test
    fun `active affinity is accepted with probabilistic ranking`() {
        val properties =
            MatchmakingRankingProperties(
                mode = MatchmakingRankingMode.PROBABILISTIC_WEIGHTED,
                affinity = MatchmakingAffinityRankingProperties(mode = MatchmakingAffinityRankingMode.ACTIVE)
            )

        assertEquals(MatchmakingAffinityRankingMode.ACTIVE, properties.affinity.mode)
    }

    @Test
    fun `invalid affinity adjustment is rejected`() {
        assertThrows<IllegalArgumentException> {
            MatchmakingAffinityRankingProperties(maxRelativeAdjustment = 0.250001)
        }
        assertThrows<IllegalArgumentException> {
            MatchmakingAffinityRankingProperties(maxRelativeAdjustment = Double.NaN)
        }
    }

    @Test
    fun `invalid affinity confidence thresholds are rejected`() {
        assertThrows<IllegalArgumentException> {
            MatchmakingAffinityRankingProperties(fullConfidenceSharedQuestions = 0)
        }
        assertThrows<IllegalArgumentException> {
            MatchmakingAffinityRankingProperties(fullConfidenceCategories = 0)
        }
        assertThrows<IllegalArgumentException> {
            MatchmakingAffinityRankingProperties(categoryFullConfidenceQuestions = 0)
        }
    }
}
