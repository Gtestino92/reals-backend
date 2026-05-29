package com.reals.backend.service

import com.reals.backend.domain.ProfilePhotoValidationRequest
import com.reals.backend.domain.ProfilePhotoValidationResult
import org.springframework.stereotype.Service

/**
 * Temporary adapter until image validation is backed by a moderation/computer-vision provider.
 */
@Service
class ProfilePhotoValidationService {

    fun validate(request: ProfilePhotoValidationRequest): ProfilePhotoValidationResult =
        ProfilePhotoValidationResult(
            isPersonPhoto = false,
            isFullBody = false
        )
}
