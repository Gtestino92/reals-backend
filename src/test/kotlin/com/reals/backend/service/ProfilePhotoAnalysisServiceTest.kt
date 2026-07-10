package com.reals.backend.service

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.photo.GoogleVisionPhotoAnalysisProperties
import com.reals.backend.service.photo.PhotoContentLikelihood
import com.reals.backend.service.photo.PhotoSafeSearchSignals
import com.reals.backend.service.photo.ProfilePhotoAnalysisProvider
import com.reals.backend.service.photo.ProfilePhotoAnalysisProviderResult
import com.reals.backend.service.photo.ProfilePhotoAnalysisService
import com.reals.backend.service.photo.ProfilePhotoAnalysisSignals
import com.reals.backend.service.photo.ProfilePhotoSemanticPolicy
import com.reals.backend.service.photo.VisionPhotoModerationPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ProfilePhotoAnalysisServiceTest {

    @Test
    fun `confidence above threshold validates person photo and never full-body`() {
        val result = serviceFor(successProvider(faceConfidences = listOf(0.90))).analyzeUploadedPhoto()

        assertEquals(PhotoValidationStatus.VALIDATED, result.validation.status)
        assertEquals(true, result.validation.isPersonPhoto)
        assertEquals(false, result.validation.isFullBody)
    }

    @Test
    fun `confidence exactly equal to threshold counts as person photo`() {
        val result = serviceFor(successProvider(faceConfidences = listOf(0.50))).analyzeUploadedPhoto()

        assertEquals(PhotoValidationStatus.VALIDATED, result.validation.status)
        assertEquals(true, result.validation.isPersonPhoto)
        assertEquals(false, result.validation.isFullBody)
    }

    @Test
    fun `all confidences below threshold validates non-person photo`() {
        val result = serviceFor(successProvider(faceConfidences = listOf(0.49, 0.10))).analyzeUploadedPhoto()

        assertEquals(PhotoValidationStatus.VALIDATED, result.validation.status)
        assertEquals(false, result.validation.isPersonPhoto)
        assertEquals(false, result.validation.isFullBody)
    }

    @Test
    fun `no faces validates non-person photo`() {
        val result = serviceFor(successProvider(faceConfidences = emptyList())).analyzeUploadedPhoto()

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
        properties: GoogleVisionPhotoAnalysisProperties = GoogleVisionPhotoAnalysisProperties()
    ): ProfilePhotoAnalysisService =
        ProfilePhotoAnalysisService(
            provider = provider,
            semanticPolicy = ProfilePhotoSemanticPolicy(properties),
            moderationPolicy = VisionPhotoModerationPolicy(properties),
            environmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles(profile),
            failUploadOnProviderError = failUploadOnProviderError
        )

    private fun successProvider(
        faceConfidences: List<Double>,
        safeSearch: PhotoSafeSearchSignals = safeSearch()
    ): ProfilePhotoAnalysisProvider =
        object : ProfilePhotoAnalysisProvider {
            override fun analyze(request: com.reals.backend.service.photo.ProfilePhotoAnalysisRequest) =
                ProfilePhotoAnalysisProviderResult.Success(
                    provider = "test",
                    signals = ProfilePhotoAnalysisSignals(
                        provider = "test",
                        faceDetectionConfidences = faceConfidences,
                        safeSearch = safeSearch
                    )
                )
        }

    private fun failureProvider(): ProfilePhotoAnalysisProvider =
        object : ProfilePhotoAnalysisProvider {
            override fun analyze(request: com.reals.backend.service.photo.ProfilePhotoAnalysisRequest) =
                ProfilePhotoAnalysisProviderResult.ProviderFailure(
                    provider = "test",
                    reason = "failed"
                )
        }

    private fun notConfiguredProvider(): ProfilePhotoAnalysisProvider =
        object : ProfilePhotoAnalysisProvider {
            override fun analyze(request: com.reals.backend.service.photo.ProfilePhotoAnalysisRequest) =
                ProfilePhotoAnalysisProviderResult.NotConfigured(provider = "none")
        }

    private fun throwingProvider(): ProfilePhotoAnalysisProvider =
        object : ProfilePhotoAnalysisProvider {
            override fun analyze(request: com.reals.backend.service.photo.ProfilePhotoAnalysisRequest):
                ProfilePhotoAnalysisProviderResult = throw RuntimeException("provider unavailable")
        }

    private fun safeSearch(): PhotoSafeSearchSignals =
        PhotoSafeSearchSignals(
            adult = PhotoContentLikelihood.UNLIKELY,
            spoof = PhotoContentLikelihood.UNLIKELY,
            medical = PhotoContentLikelihood.UNLIKELY,
            violence = PhotoContentLikelihood.UNLIKELY,
            racy = PhotoContentLikelihood.UNLIKELY
        )
}
