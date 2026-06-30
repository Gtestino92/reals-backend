package com.reals.backend.service

import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.photo.PhotoModerationProvider
import com.reals.backend.service.photo.PhotoModerationRequest
import com.reals.backend.service.photo.ProfilePhotoModerationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ProfilePhotoModerationServiceTest {

    @Test
    fun `provider failure returns needs review when fail upload is disabled`() {
        val service = ProfilePhotoModerationService(
            provider = throwingProvider(),
            failUploadOnProviderError = false
        )

        val result = service.moderateUploadedPhoto(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            photoId = UUID.randomUUID(),
            contentType = "image/jpeg",
            bytes = byteArrayOf(1)
        )

        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, result.status)
        assertEquals("provider-error", result.provider)
    }

    @Test
    fun `provider failure rejects upload when fail upload is enabled`() {
        val service = ProfilePhotoModerationService(
            provider = throwingProvider(),
            failUploadOnProviderError = true
        )

        val exception = assertThrows<DomainConflictException> {
            service.moderateUploadedPhoto(
                userId = UUID.randomUUID(),
                profileId = UUID.randomUUID(),
                photoId = UUID.randomUUID(),
                contentType = "image/jpeg",
                bytes = byteArrayOf(1)
            )
        }

        assertEquals(DomainErrorCode.PROFILE_PHOTO_MODERATION_FAILED, exception.code)
    }

    private fun throwingProvider(): PhotoModerationProvider =
        object : PhotoModerationProvider {
            override fun moderate(request: PhotoModerationRequest) =
                throw RuntimeException("provider unavailable")
        }
}
