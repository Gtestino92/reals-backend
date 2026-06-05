package com.reals.backend.service

import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.ProfilePhotoValidationResult
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("local-firebase")
class ProfilePhotoValidationService {

    fun validateUploadedPhoto(
        contentType: String,
        bytes: ByteArray,
        replacingPhoto: ProfilePhoto? = null
    ): ProfilePhotoValidationResult {
        return ProfilePhotoValidationResult(
            isPersonPhoto = true,
            isFullBody = false,
            status = PhotoValidationStatus.VALIDATED
        )
    }

    fun validateExternalUrl(
        url: String,
        replacingPhoto: ProfilePhoto? = null
    ): ProfilePhotoValidationResult {
        return ProfilePhotoValidationResult(
            isPersonPhoto = true,
            isFullBody = false,
            status = PhotoValidationStatus.VALIDATED
        )
    }
}