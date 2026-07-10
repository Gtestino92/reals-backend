package com.reals.backend.service.authenticity

import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import java.util.UUID

interface ProfileAuthenticityVerificationProvider {
    fun verify(request: ProfileAuthenticityVerificationRequest): ProfileAuthenticityVerificationResult
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

data class ProfileAuthenticityVerificationResult(
    val status: ProfileAuthenticityVerificationStatus,
    val provider: String,
    val reason: String? = null
)
