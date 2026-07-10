package com.reals.backend.service

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.photo.ProfilePhotoAnalysisProvider
import com.reals.backend.service.photo.ProfilePhotoAnalysisProviderResult
import com.reals.backend.service.photo.ProfilePhotoAnalysisRequest
import com.reals.backend.service.photo.ProfilePhotoAnalysisService
import com.reals.backend.service.photo.ProfilePhotoAnalysisSignals
import com.reals.backend.service.photo.ProfilePhotoModerationPolicy
import com.reals.backend.service.photo.ProfilePhotoModerationPolicyProperties
import com.reals.backend.service.photo.ProfilePhotoModerationSignals
import com.reals.backend.service.photo.ProfilePhotoSemanticPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ProfilePhotoAnalysisServiceTest {

    @Test
    fun `real face count greater than zero validates person photo and never full-body`() {
        val result = serviceFor(successProvider(realFaceCount = 1)).analyzeUploadedPhoto()

        assertEquals(PhotoValidationStatus.VALIDATED, result.validation.status)
        assertEquals(true, result.validation.isPersonPhoto)
        assertEquals(false, result.validation.isFullBody)
    }

    @Test
    fun `zero real faces validates non-person photo and never full-body`() {
        val result = serviceFor(successProvider(realFaceCount = 0)).analyzeUploadedPhoto()

        assertEquals(PhotoValidationStatus.VALIDATED, result.validation.status)
        assertEquals(false, result.validation.isPersonPhoto)
        assertEquals(false, result.validation.isFullBody)
    }

    @Test
    fun `provider failure returns pending semantic state when fail upload is disabled`() {
        val result = serviceFor(
            provider = failureProvider(),
            failUploadOnProviderError = false
        ).analyzeUploadedPhoto()

        assertEquals(PhotoValidationStatus.PENDING, result.validation.status)
        assertEquals(false, result.validation.isPersonPhoto)
        assertEquals(false, result.validation.isFullBody)
        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, result.moderation.status)
    }

    @Test
    fun `provider exception returns pending semantic state when fail upload is disabled`() {
        val result = serviceFor(
            provider = throwingProvider(),
            failUploadOnProviderError = false
        ).analyzeUploadedPhoto()

        assertEquals(PhotoValidationStatus.PENDING, result.validation.status)
        assertEquals(false, result.validation.isPersonPhoto)
        assertEquals(false, result.validation.isFullBody)
        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, result.moderation.status)
    }

    @Test
    fun `provider failure rejects upload when fail upload is enabled`() {
        val exception = assertThrows<DomainConflictException> {
            serviceFor(
                provider = failureProvider(),
                failUploadOnProviderError = true
            ).analyzeUploadedPhoto()
        }

        assertEquals(DomainErrorCode.PROFILE_PHOTO_MODERATION_FAILED, exception.code)
    }

    @Test
    fun `none outside prod preserves MVP semantic and moderation shortcuts`() {
        val result = serviceFor(
            provider = notConfiguredProvider(),
            profile = "test"
        ).analyzeUploadedPhoto()

        assertEquals(PhotoValidationStatus.VALIDATED, result.validation.status)
        assertEquals(true, result.validation.isPersonPhoto)
        assertEquals(true, result.validation.isFullBody)
        assertEquals(PhotoModerationStatus.APPROVED, result.moderation.status)
    }

    @Test
    fun `none in prod preserves pending semantic and needs-review moderation behavior`() {
        val result = serviceFor(
            provider = notConfiguredProvider(),
            profile = "prod"
        ).analyzeUploadedPhoto()

        assertEquals(PhotoValidationStatus.PENDING, result.validation.status)
        assertEquals(false, result.validation.isPersonPhoto)
        assertEquals(false, result.validation.isFullBody)
        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, result.moderation.status)
    }

    private fun ProfilePhotoAnalysisService.analyzeUploadedPhoto() =
        analyzeUploadedPhoto(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            photoId = UUID.randomUUID(),
            contentType = "image/jpeg",
            bytes = byteArrayOf(1)
        )

    private fun serviceFor(
        provider: ProfilePhotoAnalysisProvider,
        profile: String = "test",
        failUploadOnProviderError: Boolean = false,
        properties: ProfilePhotoModerationPolicyProperties = ProfilePhotoModerationPolicyProperties()
    ): ProfilePhotoAnalysisService =
        ProfilePhotoAnalysisService(
            provider = provider,
            semanticPolicy = ProfilePhotoSemanticPolicy(),
            moderationPolicy = ProfilePhotoModerationPolicy(properties),
            environmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles(profile),
            failUploadOnProviderError = failUploadOnProviderError
        )

    private fun successProvider(
        realFaceCount: Int,
        moderation: ProfilePhotoModerationSignals = lowModerationSignals()
    ): ProfilePhotoAnalysisProvider =
        object : ProfilePhotoAnalysisProvider {
            override fun analyze(request: ProfilePhotoAnalysisRequest) =
                ProfilePhotoAnalysisProviderResult.Success(
                    provider = "test",
                    signals = ProfilePhotoAnalysisSignals(
                        provider = "test",
                        realFaceCount = realFaceCount,
                        moderation = moderation
                    )
                )
        }

    private fun failureProvider(): ProfilePhotoAnalysisProvider =
        object : ProfilePhotoAnalysisProvider {
            override fun analyze(request: ProfilePhotoAnalysisRequest) =
                ProfilePhotoAnalysisProviderResult.ProviderFailure(
                    provider = "test",
                    reason = "failed"
                )
        }

    private fun notConfiguredProvider(): ProfilePhotoAnalysisProvider =
        object : ProfilePhotoAnalysisProvider {
            override fun analyze(request: ProfilePhotoAnalysisRequest) =
                ProfilePhotoAnalysisProviderResult.NotConfigured(provider = "none")
        }

    private fun throwingProvider(): ProfilePhotoAnalysisProvider =
        object : ProfilePhotoAnalysisProvider {
            override fun analyze(request: ProfilePhotoAnalysisRequest):
                ProfilePhotoAnalysisProviderResult = throw RuntimeException("provider unavailable")
        }

    private fun lowModerationSignals(): ProfilePhotoModerationSignals =
        ProfilePhotoModerationSignals(
            sexualExplicit = 0.0,
            sexualSuggestive = 0.0,
            violenceOrThreat = 0.0,
            gore = 0.0,
            hateOrExtremism = 0.0
        )
}
