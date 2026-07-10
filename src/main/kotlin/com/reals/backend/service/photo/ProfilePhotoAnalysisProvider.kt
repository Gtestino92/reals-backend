package com.reals.backend.service.photo

import com.reals.backend.domain.PhotoModerationStatus
import java.util.UUID

interface ProfilePhotoAnalysisProvider {
    fun analyze(request: ProfilePhotoAnalysisRequest): ProfilePhotoAnalysisProviderResult
}

data class ProfilePhotoAnalysisRequest(
    val userId: UUID,
    val profileId: UUID,
    val photoId: UUID,
    val contentType: String,
    val bytes: ByteArray
)

sealed interface ProfilePhotoAnalysisProviderResult {
    val provider: String

    data class Success(
        override val provider: String,
        val signals: ProfilePhotoAnalysisSignals
    ) : ProfilePhotoAnalysisProviderResult

    data class NotConfigured(
        override val provider: String
    ) : ProfilePhotoAnalysisProviderResult

    data class ProviderFailure(
        override val provider: String,
        val reason: String? = null
    ) : ProfilePhotoAnalysisProviderResult
}

data class ProfilePhotoAnalysisSignals(
    val provider: String,
    val faceDetectionConfidences: List<Double>,
    val safeSearch: PhotoSafeSearchSignals
)

data class PhotoSafeSearchSignals(
    val adult: PhotoContentLikelihood,
    val spoof: PhotoContentLikelihood,
    val medical: PhotoContentLikelihood,
    val violence: PhotoContentLikelihood,
    val racy: PhotoContentLikelihood
)

enum class PhotoContentLikelihood(private val severity: Int?) {
    UNKNOWN(null),
    VERY_UNLIKELY(0),
    UNLIKELY(1),
    POSSIBLE(2),
    LIKELY(3),
    VERY_LIKELY(4);

    fun isKnownAtLeast(threshold: PhotoContentLikelihood): Boolean {
        val currentSeverity = severity ?: return false
        val thresholdSeverity = threshold.severity
            ?: throw IllegalArgumentException("UNKNOWN cannot be used as a policy threshold")
        return currentSeverity >= thresholdSeverity
    }

    fun isMoreRestrictiveOrEqualTo(threshold: PhotoContentLikelihood): Boolean {
        val currentSeverity = severity
            ?: throw IllegalArgumentException("UNKNOWN cannot be used as a policy threshold")
        val thresholdSeverity = threshold.severity
            ?: throw IllegalArgumentException("UNKNOWN cannot be used as a policy threshold")
        return currentSeverity >= thresholdSeverity
    }
}

data class PhotoModerationResult(
    val status: PhotoModerationStatus,
    val provider: String,
    val reason: String? = null
)
