package com.reals.backend.service

import org.springframework.stereotype.Service

data class ProfilePhotoValidationRequest(
    val url: String
)

data class ProfilePhotoValidationResult(
    val isPersonPhoto: Boolean,
    val isFullBody: Boolean
)

interface ProfilePhotoValidationService {
    fun validate(request: ProfilePhotoValidationRequest): ProfilePhotoValidationResult
}

/**
 * Temporary adapter until image validation is backed by a moderation/computer-vision provider.
 */
@Service
class PendingProfilePhotoValidationService : ProfilePhotoValidationService {

    override fun validate(request: ProfilePhotoValidationRequest): ProfilePhotoValidationResult =
        ProfilePhotoValidationResult(
            isPersonPhoto = false,
            isFullBody = false
        )
}
