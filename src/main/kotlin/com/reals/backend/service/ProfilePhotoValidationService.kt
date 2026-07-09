package com.reals.backend.service

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.s3.ProfilePhotoStorageProperties
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.ProfilePhotoValidationResult
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

@Service
class ProfilePhotoValidationService(
    private val properties: ProfilePhotoStorageProperties,
    private val environmentExposurePolicy: EnvironmentExposurePolicy
) {

    fun validateUploadedPhoto(
        contentType: String,
        bytes: ByteArray,
        replacingPhoto: ProfilePhoto? = null
    ): ProfilePhotoValidationResult {
        validateImageDecodesAndDimensions(
            contentType = contentType,
            bytes = bytes
        )

        if (environmentExposurePolicy.isProduction()) {
            return ProfilePhotoValidationResult(
                isPersonPhoto = false,
                isFullBody = false,
                status = PhotoValidationStatus.PENDING
            )
        }

        // Temporary MVP shortcut for non-production compatibility only.
        return ProfilePhotoValidationResult(
            isPersonPhoto = true,
            isFullBody = true,
            status = PhotoValidationStatus.VALIDATED
        )
    }

    private fun validateImageDecodesAndDimensions(
        contentType: String,
        bytes: ByteArray
    ) {
        if (contentType == "image/webp") {
            // Standard JVM ImageIO does not reliably decode WebP without extra plugins.
            return
        }

        val image = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            null
        }

        if (image == null) {
            throw invalidProfilePhoto("Photo file could not be decoded as an image")
        }

        if (
            image.width <= 0 ||
            image.height <= 0 ||
            image.width > properties.maxWidthPixels ||
            image.height > properties.maxHeightPixels
        ) {
            throw invalidProfilePhoto("Photo dimensions are invalid")
        }
    }

    private fun invalidProfilePhoto(message: String): DomainBadRequestException =
        DomainBadRequestException(
            code = DomainErrorCode.INVALID_PROFILE_PHOTO,
            message = message
        )
}
