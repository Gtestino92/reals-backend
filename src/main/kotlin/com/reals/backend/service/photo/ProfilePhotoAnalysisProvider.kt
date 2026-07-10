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
    val realFaceCount: Int,
    val moderation: ProfilePhotoModerationSignals
)

data class ProfilePhotoModerationSignals(
    val sexualExplicit: Double,
    val sexualSuggestive: Double,
    val violenceOrThreat: Double,
    val gore: Double,
    val hateOrExtremism: Double
)

data class PhotoModerationResult(
    val status: PhotoModerationStatus,
    val provider: String,
    val reason: String? = null
)
