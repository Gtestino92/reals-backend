package com.reals.backend.service.photo

import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.Profile
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
import java.time.LocalDate
import java.util.UUID

class ProfilePhotoModerationReviewServiceTest {

    @Test
    fun `admin review read url uses persisted bucket and key`() {
        val photoRepository = Mockito.mock(ProfilePhotoRepository::class.java)
        val profileRepository = Mockito.mock(ProfileRepository::class.java)
        val storageService = Mockito.mock(S3StorageService::class.java)
        val auditEventService = Mockito.mock(AuditEventService::class.java)
        val photo = photo(PhotoModerationStatus.NEEDS_REVIEW)
        val profile = Profile(
            id = photo.profileId,
            userId = UUID.randomUUID(),
            displayName = "Review User",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = com.reals.backend.domain.Gender.FEMALE,
            lookingForGenders = mutableSetOf(com.reals.backend.domain.Gender.MALE),
            intention = com.reals.backend.domain.Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR"
        )
        Mockito.`when`(
            photoRepository.findTop100ByModerationStatusOrderByCreatedAtAsc(
                PhotoModerationStatus.NEEDS_REVIEW
            )
        ).thenReturn(listOf(photo))
        Mockito.`when`(profileRepository.findAllById(listOf(photo.profileId))).thenReturn(listOf(profile))
        Mockito.`when`(
            storageService.getReadUrl(
                "test-bucket",
                "users/test/profile-photos/photo.jpg"
            )
        ).thenReturn("https://media.example.test/photo.jpg")
        val service = ProfilePhotoModerationReviewService(
            profilePhotoRepository = photoRepository,
            profileRepository = profileRepository,
            storageService = storageService,
            auditEventService = auditEventService
        )

        val review = service.listNeedsReview().single()

        assertEquals("https://media.example.test/photo.jpg", review.readUrl)
        Mockito.verify(storageService).getReadUrl(
            "test-bucket",
            "users/test/profile-photos/photo.jpg"
        )
        Mockito.verify(storageService, Mockito.never()).getReadUrl(photo.storageKey)
    }

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
                    expectedPhotoVersion = photo.version,
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
