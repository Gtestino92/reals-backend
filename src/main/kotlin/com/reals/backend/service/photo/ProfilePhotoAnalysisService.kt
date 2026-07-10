package com.reals.backend.service.photo

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhotoValidationResult
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProfilePhotoAnalysisService(
    private val provider: ProfilePhotoAnalysisProvider,
    private val semanticPolicy: ProfilePhotoSemanticPolicy,
    private val moderationPolicy: VisionPhotoModerationPolicy,
    private val environmentExposurePolicy: EnvironmentExposurePolicy,

    @param:Value("\${profile.photos.moderation.fail-upload-on-provider-error:false}")
    private val failUploadOnProviderError: Boolean
) {

    private val logger = LoggerFactory.getLogger(ProfilePhotoAnalysisService::class.java)

    fun analyzeUploadedPhoto(
        userId: UUID,
        profileId: UUID,
        photoId: UUID,
        contentType: String,
        bytes: ByteArray
    ): ProfilePhotoAnalysisDecision {
        val result = try {
            provider.analyze(
                ProfilePhotoAnalysisRequest(
                    userId = userId,
                    profileId = profileId,
                    photoId = photoId,
                    contentType = contentType,
                    bytes = bytes
                )
            )
        } catch (ex: Exception) {
            logger.warn(
                "Profile photo analysis provider threw an exception of type {}",
                ex.javaClass.simpleName
            )
            ProfilePhotoAnalysisProviderResult.ProviderFailure(
                provider = "provider-error",
                reason = "Photo analysis provider failed"
            )
        }

        return when (result) {
            is ProfilePhotoAnalysisProviderResult.Success -> ProfilePhotoAnalysisDecision(
                validation = semanticPolicy.evaluate(result.signals),
                moderation = moderationPolicy.evaluate(result.signals)
            )

            is ProfilePhotoAnalysisProviderResult.NotConfigured -> notConfiguredDecision(result.provider)

            is ProfilePhotoAnalysisProviderResult.ProviderFailure -> providerFailureDecision(result.provider)
        }
    }

    private fun notConfiguredDecision(provider: String): ProfilePhotoAnalysisDecision {
        if (environmentExposurePolicy.isProduction()) {
            return ProfilePhotoAnalysisDecision(
                validation = ProfilePhotoValidationResult(
                    isPersonPhoto = false,
                    isFullBody = false,
                    status = PhotoValidationStatus.PENDING
                ),
                moderation = PhotoModerationResult(
                    status = PhotoModerationStatus.NEEDS_REVIEW,
                    provider = provider,
                    reason = "Photo analysis provider is not configured"
                )
            )
        }

        return ProfilePhotoAnalysisDecision(
            validation = ProfilePhotoValidationResult(
                isPersonPhoto = true,
                isFullBody = true,
                status = PhotoValidationStatus.VALIDATED
            ),
            moderation = PhotoModerationResult(
                status = PhotoModerationStatus.APPROVED,
                provider = provider
            )
        )
    }

    private fun providerFailureDecision(provider: String): ProfilePhotoAnalysisDecision {
        if (failUploadOnProviderError) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_PHOTO_MODERATION_FAILED,
                message = "Photo moderation provider failed"
            )
        }

        return ProfilePhotoAnalysisDecision(
            validation = ProfilePhotoValidationResult(
                isPersonPhoto = false,
                isFullBody = false,
                status = PhotoValidationStatus.PENDING
            ),
            moderation = PhotoModerationResult(
                status = PhotoModerationStatus.NEEDS_REVIEW,
                provider = provider,
                reason = "Photo analysis provider failed"
            )
        )
    }
}

data class ProfilePhotoAnalysisDecision(
    val validation: ProfilePhotoValidationResult,
    val moderation: PhotoModerationResult
)
