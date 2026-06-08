package com.reals.backend.service

import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.ProfilePhotoValidationResult
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service

@Service
class ProfilePhotoValidationService(
    private val environment: Environment
) {

    fun validateUploadedPhoto(
        contentType: String,
        bytes: ByteArray,
        replacingPhoto: ProfilePhoto? = null
    ): ProfilePhotoValidationResult {
        return ProfilePhotoValidationResult(
            isPersonPhoto = true,
            isFullBody = false,
            status = if (usesLocalValidation()) {
                PhotoValidationStatus.VALIDATED
            } else {
                PhotoValidationStatus.PENDING
            }
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

    private fun usesLocalValidation(): Boolean =
        environment.activeProfiles.any { profile ->
            profile in setOf("local-firebase", "local", "local-nodb", "local-postgres", "test")
        }
}
