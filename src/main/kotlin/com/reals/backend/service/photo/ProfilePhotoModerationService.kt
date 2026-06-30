package com.reals.backend.service.photo

import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainConflictException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProfilePhotoModerationService(
    private val provider: PhotoModerationProvider,

    @param:Value("\${profile.photos.moderation.fail-upload-on-provider-error:false}")
    private val failUploadOnProviderError: Boolean
) {

    fun moderateUploadedPhoto(
        userId: UUID,
        profileId: UUID,
        photoId: UUID,
        contentType: String,
        bytes: ByteArray
    ): PhotoModerationResult =
        try {
            provider.moderate(
                PhotoModerationRequest(
                    userId = userId,
                    profileId = profileId,
                    photoId = photoId,
                    contentType = contentType,
                    bytes = bytes
                )
            )
        } catch (ex: Exception) {
            if (failUploadOnProviderError) {
                throw DomainConflictException(
                    code = DomainErrorCode.PROFILE_PHOTO_MODERATION_FAILED,
                    message = "Photo moderation provider failed"
                )
            }

            PhotoModerationResult(
                status = PhotoModerationStatus.NEEDS_REVIEW,
                provider = "provider-error",
                reason = ex.message
            )
        }
}
