package com.reals.backend.service.authenticity

import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import java.util.UUID

interface ProfileAuthenticityVerificationProvider {
    fun verify(request: ProfileAuthenticityVerificationRequest): ProfileAuthenticityVerificationProviderResult
}

data class ProfileAuthenticityPhotoCandidate(
    val photoId: UUID,
    val photoVersion: Long,
    val storageKey: String
)

data class ProfileAuthenticityVerificationRequest(
    val userId: UUID,
    val profileId: UUID,
    val personPhotos: List<ProfileAuthenticityPhotoCandidate>
)

sealed interface ProfileAuthenticityVerificationProviderResult {
    val provider: String

    class Success(
        val signals: ProfileAuthenticityVerificationSignals
    ) : ProfileAuthenticityVerificationProviderResult {
        override val provider: String = signals.provider
    }

    data class NotConfigured(
        override val provider: String
    ) : ProfileAuthenticityVerificationProviderResult

    data class ProviderFailure(
        override val provider: String,
        val reason: String? = null
    ) : ProfileAuthenticityVerificationProviderResult
}

data class ProfileAuthenticityVerificationSignals(
    val provider: String,
    val liveReferenceAccepted: Boolean,
    val photoComparisons: List<ProfileAuthenticityPhotoComparison>
)

data class ProfileAuthenticityPhotoComparison(
    val photoId: UUID,
    val outcome: ProfileAuthenticityPhotoComparisonOutcome
)

enum class ProfileAuthenticityPhotoComparisonOutcome {
    MATCHED,
    UNRESOLVED,
    CONTRADICTORY
}

data class ProfileAuthenticityVerificationResult(
    val status: ProfileAuthenticityVerificationStatus,
    val provider: String,
    val reason: String? = null
)
