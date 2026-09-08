package com.reals.backend.service.photo

import com.reals.backend.domain.PhotoModerationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfilePhotoModerationPolicyTest {

    private val policy = ProfilePhotoModerationPolicy(ProfilePhotoModerationPolicyProperties())

    @Test
    fun `all signals below review thresholds are approved`() {
        assertEquals(PhotoModerationStatus.APPROVED, policy.evaluate(signals()).status)
    }

    @Test
    fun `explicit sexual exact review threshold needs review`() {
        assertEquals(
            PhotoModerationStatus.NEEDS_REVIEW,
            policy.evaluate(signals(sexualExplicit = 0.50)).status
        )
    }

    @Test
    fun `explicit sexual exact reject threshold is rejected`() {
        assertEquals(
            PhotoModerationStatus.REJECTED,
            policy.evaluate(signals(sexualExplicit = 0.80)).status
        )
    }

    @Test
    fun `suggestive exact review threshold needs review`() {
        assertEquals(
            PhotoModerationStatus.NEEDS_REVIEW,
            policy.evaluate(signals(sexualSuggestive = 0.50)).status
        )
    }

    @Test
    fun `normal swimwear smoke signals are approved`() {
        assertEquals(
            PhotoModerationStatus.APPROVED,
            policy.evaluate(
                signals(
                    sexualExplicit = 0.001,
                    sexualSuggestive = 0.05
                )
            ).status
        )
    }

    @Test
    fun `sexualized but non explicit smoke signals need review`() {
        assertEquals(
            PhotoModerationStatus.NEEDS_REVIEW,
            policy.evaluate(
                signals(
                    sexualExplicit = 0.01,
                    sexualSuggestive = 0.99
                )
            ).status
        )
    }

    @Test
    fun `explicit smoke signals are rejected`() {
        assertEquals(
            PhotoModerationStatus.REJECTED,
            policy.evaluate(
                signals(
                    sexualExplicit = 0.99,
                    sexualSuggestive = 0.99
                )
            ).status
        )
    }

    @Test
    fun `suggestive alone never auto rejects`() {
        assertEquals(
            PhotoModerationStatus.NEEDS_REVIEW,
            policy.evaluate(signals(sexualSuggestive = 1.0)).status
        )
    }

    @Test
    fun `violence exact review threshold needs review`() {
        assertEquals(
            PhotoModerationStatus.NEEDS_REVIEW,
            policy.evaluate(signals(violenceOrThreat = 0.50)).status
        )
    }

    @Test
    fun `violence exact reject threshold is rejected`() {
        assertEquals(
            PhotoModerationStatus.REJECTED,
            policy.evaluate(signals(violenceOrThreat = 0.85)).status
        )
    }

    @Test
    fun `gore exact review threshold needs review`() {
        assertEquals(
            PhotoModerationStatus.NEEDS_REVIEW,
            policy.evaluate(signals(gore = 0.40)).status
        )
    }

    @Test
    fun `gore exact reject threshold is rejected`() {
        assertEquals(
            PhotoModerationStatus.REJECTED,
            policy.evaluate(signals(gore = 0.80)).status
        )
    }

    @Test
    fun `hate exact review threshold needs review`() {
        assertEquals(
            PhotoModerationStatus.NEEDS_REVIEW,
            policy.evaluate(signals(hateOrExtremism = 0.50)).status
        )
    }

    @Test
    fun `hate exact reject threshold is rejected`() {
        assertEquals(
            PhotoModerationStatus.REJECTED,
            policy.evaluate(signals(hateOrExtremism = 0.85)).status
        )
    }

    @Test
    fun `reject takes precedence over review`() {
        assertEquals(
            PhotoModerationStatus.REJECTED,
            policy.evaluate(
                signals(
                    sexualSuggestive = 1.0,
                    gore = 0.80
                )
            ).status
        )
    }

    private fun signals(
        sexualExplicit: Double = 0.0,
        sexualSuggestive: Double = 0.0,
        violenceOrThreat: Double = 0.0,
        gore: Double = 0.0,
        hateOrExtremism: Double = 0.0
    ): ProfilePhotoAnalysisSignals =
        ProfilePhotoAnalysisSignals(
            provider = "sightengine",
            realFaceCount = 0,
            moderation = ProfilePhotoModerationSignals(
                sexualExplicit = sexualExplicit,
                sexualSuggestive = sexualSuggestive,
                violenceOrThreat = violenceOrThreat,
                gore = gore,
                hateOrExtremism = hateOrExtremism
            )
        )
}
