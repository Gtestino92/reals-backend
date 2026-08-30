package com.reals.backend.config.environment

import com.reals.backend.config.security.ratelimit.RateLimitProperties
import com.reals.backend.service.photo.ProfilePhotoModerationRuntimeProperties
import com.reals.backend.service.photo.ProfilePhotoRuntimeProperties
import com.reals.backend.service.photo.SIGHTENGINE_PROVIDER
import com.reals.backend.service.photo.SightenginePhotoAnalysisProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProductionRuntimeGuardrailsTest {

    @Test
    fun `prod rejects photo provider none`() {
        val exception = assertThrows<IllegalArgumentException> {
            guard(
                profilePhotoProperties = profilePhotoProperties(provider = "none")
            ).afterPropertiesSet()
        }

        assertEquals(
            "profile.photos.moderation.provider must be sightengine in prod",
            exception.message
        )
    }

    @Test
    fun `prod rejects structurally invalid Sightengine endpoint`() {
        val exception = assertThrows<IllegalArgumentException> {
            guard(
                sightengineProperties = sightengineProperties(endpoint = "http://api.sightengine.com/1.0/check.json")
            ).afterPropertiesSet()
        }

        assertEquals(
            "profile.photos.sightengine.endpoint must be a valid absolute HTTPS URI in prod",
            exception.message
        )
    }

    @Test
    fun `prod rejects disabled moderation approval for activation`() {
        val exception = assertThrows<IllegalArgumentException> {
            guard(
                profilePhotoProperties = profilePhotoProperties(
                    requireModerationApprovalForActivation = false
                )
            ).afterPropertiesSet()
        }

        assertEquals(
            "profile.photos.require-moderation-approval-for-activation must be true in prod",
            exception.message
        )
    }

    @Test
    fun `prod rejects disabled rate limiting`() {
        val exception = assertThrows<IllegalArgumentException> {
            guard(rateLimitProperties = RateLimitProperties(enabled = false))
                .afterPropertiesSet()
        }

        assertEquals(
            "security.rate-limit.enabled must be true in prod",
            exception.message
        )
    }

    @Test
    fun `valid prod structural configuration is accepted`() {
        assertDoesNotThrow {
            guard().afterPropertiesSet()
        }
    }

    @Test
    fun `non prod keeps existing relaxed photo moderation and rate limit behavior`() {
        assertDoesNotThrow {
            guard(
                environmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles("dev"),
                profilePhotoProperties = profilePhotoProperties(
                    provider = "none",
                    requireModerationApprovalForActivation = false
                ),
                rateLimitProperties = RateLimitProperties(enabled = false),
                sightengineProperties = sightengineProperties(endpoint = "not a uri")
            ).afterPropertiesSet()
        }
    }

    private fun guard(
        environmentExposurePolicy: EnvironmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles("prod"),
        profilePhotoProperties: ProfilePhotoRuntimeProperties = profilePhotoProperties(),
        sightengineProperties: SightenginePhotoAnalysisProperties = sightengineProperties(),
        rateLimitProperties: RateLimitProperties = RateLimitProperties()
    ): ProductionRuntimeGuardrails =
        ProductionRuntimeGuardrails(
            environmentExposurePolicy = environmentExposurePolicy,
            profilePhotoProperties = profilePhotoProperties,
            sightengineProperties = sightengineProperties,
            rateLimitProperties = rateLimitProperties
        )

    private fun profilePhotoProperties(
        provider: String = SIGHTENGINE_PROVIDER,
        requireModerationApprovalForActivation: Boolean = true
    ): ProfilePhotoRuntimeProperties =
        ProfilePhotoRuntimeProperties(
            requireModerationApprovalForActivation = requireModerationApprovalForActivation,
            moderation = ProfilePhotoModerationRuntimeProperties(provider = provider)
        )

    private fun sightengineProperties(
        endpoint: String = "https://api.sightengine.com/1.0/check.json"
    ): SightenginePhotoAnalysisProperties =
        SightenginePhotoAnalysisProperties(
            endpoint = endpoint,
            apiUser = "test-user",
            apiSecret = "test-secret"
        )
}
