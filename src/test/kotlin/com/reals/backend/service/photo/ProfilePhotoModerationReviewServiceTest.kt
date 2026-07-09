package com.reals.backend.service.photo

import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.AuditEventService
import com.reals.backend.service.S3StorageService
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.util.UUID

class ProfilePhotoModerationReviewServiceTest {

    @Test
    fun `non-review states cannot be resolved through admin review`() {
        listOf(
            PhotoModerationStatus.PENDING,
            PhotoModerationStatus.APPROVED,
            PhotoModerationStatus.REJECTED
        ).forEach { status ->
            val photoRepository = Mockito.mock(ProfilePhotoRepository::class.java)
            val auditEventService = Mockito.mock(AuditEventService::class.java)
            val photo = photo(status)
            Mockito.`when`(photoRepository.findByIdForUpdate(photo.id))
                .thenReturn(photo)
            val service = service(
                photoRepository = photoRepository,
                auditEventService = auditEventService
            )

            val exception = assertThrows<DomainConflictException> {
                service.resolve(
                    photoId = photo.id,
                    adminUserId = UUID.randomUUID(),
                    decision = AdminPhotoModerationDecision.APPROVED,
                    notes = "Review"
                )
            }

            assertEquals(DomainErrorCode.PROFILE_PHOTO_MODERATION_REVIEW_NOT_AVAILABLE, exception.code)
            Mockito.verify(photoRepository, Mockito.never()).save(Mockito.any(ProfilePhoto::class.java))
            Mockito.verifyNoInteractions(auditEventService)
        }
    }

    private fun service(
        photoRepository: ProfilePhotoRepository,
        auditEventService: AuditEventService
    ): ProfilePhotoModerationReviewService =
        ProfilePhotoModerationReviewService(
            profilePhotoRepository = photoRepository,
            profileRepository = Mockito.mock(ProfileRepository::class.java),
            storageService = Mockito.mock(S3StorageService::class.java),
            auditEventService = auditEventService
        )

    private fun photo(status: PhotoModerationStatus): ProfilePhoto =
        ProfilePhoto(
            id = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            storageProvider = PhotoStorageProvider.S3,
            storageBucket = "test-bucket",
            storageKey = "users/test/profile-photos/photo.jpg",
            position = 1,
            validationStatus = PhotoValidationStatus.VALIDATED,
            moderationStatus = status
        )
}
