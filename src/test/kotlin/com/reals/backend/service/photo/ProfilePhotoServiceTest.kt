package com.reals.backend.service.photo

import com.reals.backend.config.s3.ProfilePhotoValidationProperties
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.MediaCleanupTask
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.StoredObject
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.AuditEventService
import com.reals.backend.service.HomeStateInvalidationService
import com.reals.backend.service.MediaCleanupProcessor
import com.reals.backend.service.MediaCleanupTaskService
import com.reals.backend.service.ProfilePhotoNormalizer
import com.reals.backend.service.S3StorageService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

class ProfilePhotoServiceTest {

    @Test
    fun `profile photo read url uses persisted bucket and key`() {
        val photo = ProfilePhoto(
            id = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            storageProvider = PhotoStorageProvider.S3,
            storageBucket = "persisted-media-bucket",
            storageKey = "users/user-id/profile-photos/photo.jpg",
            position = 1,
            validationStatus = PhotoValidationStatus.VALIDATED,
            moderationStatus = PhotoModerationStatus.APPROVED
        )
        val storageService = Mockito.mock(S3StorageService::class.java)
        Mockito.`when`(
            storageService.getReadUrl(
                "persisted-media-bucket",
                "users/user-id/profile-photos/photo.jpg"
            )
        ).thenReturn("https://media.example.test/photo.jpg")
        val service = profilePhotoService(
            profileRepository = Mockito.mock(ProfileRepository::class.java),
            auditEventService = Mockito.mock(AuditEventService::class.java),
            storageService = storageService
        )

        assertEquals(
            "https://media.example.test/photo.jpg",
            service.resolvePhotoReadUrl(photo)
        )
        Mockito.verify(storageService).getReadUrl(
            "persisted-media-bucket",
            "users/user-id/profile-photos/photo.jpg"
        )
        Mockito.verify(storageService, Mockito.never()).getReadUrl(photo.storageKey)
    }

    @Test
    fun `external photo views include only approved photos and resolve only included urls`() {
        val profile = Profile(
            id = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            displayName = "External Visibility",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = mutableSetOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR"
        )
        val approved = photo(profile.id, position = 2, status = PhotoModerationStatus.APPROVED, key = "approved.jpg")
        val needsReview = photo(profile.id, position = 1, status = PhotoModerationStatus.NEEDS_REVIEW, key = "review.jpg")
        val rejected = photo(profile.id, position = 3, status = PhotoModerationStatus.REJECTED, key = "rejected.jpg")
        val profileRepository = Mockito.mock(ProfileRepository::class.java)
        val profilePhotoRepository = Mockito.mock(ProfilePhotoRepository::class.java)
        val storageService = Mockito.mock(S3StorageService::class.java)
        Mockito.`when`(profileRepository.findById(profile.id))
            .thenReturn(Optional.of(profile))
        Mockito.`when`(profilePhotoRepository.findByProfileId(profile.id))
            .thenReturn(listOf(needsReview, approved, rejected))
        Mockito.`when`(storageService.getReadUrl("test-bucket", approved.storageKey))
            .thenReturn("https://media.example.test/approved.jpg")
        val service = profilePhotoService(
            profileRepository = profileRepository,
            profilePhotoRepository = profilePhotoRepository,
            auditEventService = Mockito.mock(AuditEventService::class.java),
            storageService = storageService
        )

        val views = service.getExternallyVisiblePhotoViews(profile.id)

        assertEquals(listOf(approved.id), views.map { it.photo.id })
        assertEquals(listOf("https://media.example.test/approved.jpg"), views.map { it.readUrl })
        Mockito.verify(storageService).getReadUrl("test-bucket", approved.storageKey)
        Mockito.verify(storageService, Mockito.never()).getReadUrl("test-bucket", needsReview.storageKey)
        Mockito.verify(storageService, Mockito.never()).getReadUrl("test-bucket", rejected.storageKey)
    }

    @Test
    fun `successful photo mutation resynchronizes invalid not-started authenticity boolean without stale audit`() {
        val profile = Profile(
            id = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            displayName = "Authenticity Test",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = mutableSetOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            authenticityVerificationStatus = ProfileAuthenticityVerificationStatus.NOT_STARTED,
            authenticityVerified = true
        )
        val photo = ProfilePhoto(
            id = UUID.randomUUID(),
            profileId = profile.id,
            storageProvider = PhotoStorageProvider.S3,
            storageBucket = "test-bucket",
            storageKey = "profile/${profile.id}/photo.jpg",
            position = 1,
            isPersonPhoto = true,
            isFullBody = false,
            validationStatus = PhotoValidationStatus.VALIDATED,
            moderationStatus = PhotoModerationStatus.APPROVED
        )
        val profileRepository = Mockito.mock(ProfileRepository::class.java)
        val profilePhotoRepository = Mockito.mock(ProfilePhotoRepository::class.java)
        val auditEventService = Mockito.mock(AuditEventService::class.java)
        Mockito.`when`(profileRepository.findById(profile.id))
            .thenReturn(Optional.of(profile))
        Mockito.`when`(profilePhotoRepository.findByIdForUpdate(photo.id))
            .thenReturn(photo)
        Mockito.`when`(profileRepository.save(anyProfile()))
            .thenAnswer { invocation -> invocation.getArgument<Profile>(0) }
        val cleanupTaskService = Mockito.mock(MediaCleanupTaskService::class.java)
        Mockito.`when`(
            cleanupTaskService.createImmediateDeleteTaskInCurrentTransaction(
                anyStoredObject(),
                anyPhotoStorageProvider(),
                anyOffsetDateTime()
            )
        )
            .thenReturn(
                MediaCleanupTask(
                    bucket = "test-bucket",
                    objectKey = photo.storageKey,
                    nextAttemptAt = OffsetDateTime.now()
                )
            )

        val service = profilePhotoService(
            profileRepository = profileRepository,
            profilePhotoRepository = profilePhotoRepository,
            auditEventService = auditEventService,
            mediaCleanupTaskService = cleanupTaskService
        )

        val saved = service.deletePhoto(profile.id, photo.id)

        assertEquals(ProfileAuthenticityVerificationStatus.NOT_STARTED, saved.authenticityVerificationStatus)
        assertEquals(false, saved.authenticityVerified)

        val eventTypes = Mockito.mockingDetails(auditEventService)
            .invocations
            .map { invocation -> invocation.arguments[0] as AuditEventType }
        assertEquals(listOf(AuditEventType.PROFILE_PHOTO_DELETED), eventTypes)
    }

    private fun profilePhotoService(
        profileRepository: ProfileRepository,
        auditEventService: AuditEventService,
        profilePhotoRepository: ProfilePhotoRepository = Mockito.mock(ProfilePhotoRepository::class.java),
        storageService: S3StorageService = Mockito.mock(S3StorageService::class.java),
        mediaCleanupTaskService: MediaCleanupTaskService = Mockito.mock(MediaCleanupTaskService::class.java),
        mediaCleanupProcessor: MediaCleanupProcessor = Mockito.mock(MediaCleanupProcessor::class.java)
    ): ProfilePhotoService =
        ProfilePhotoService(
            profileRepository = profileRepository,
            profilePhotoRepository = profilePhotoRepository,
            profilePhotoNormalizer = Mockito.mock(ProfilePhotoNormalizer::class.java),
            profilePhotoAnalysisService = Mockito.mock(ProfilePhotoAnalysisService::class.java),
            storageService = storageService,
            mediaCleanupTaskService = mediaCleanupTaskService,
            mediaCleanupProcessor = mediaCleanupProcessor,
            transactionTemplate = TransactionTemplate(NoOpTransactionManager()),
            profilePhotoValidationProperties = ProfilePhotoValidationProperties(),
            auditEventService = auditEventService,
            homeStateInvalidationService = Mockito.mock(HomeStateInvalidationService::class.java),
            maxPhotoCount = 9,
            requiredPhotoCount = 9,
            minPersonPhotos = 3,
            minFullBodyPhotos = 1,
            persistRejectedPhotos = false,
            requireModerationApprovalForActivation = true
        )

    private fun photo(
        profileId: UUID,
        position: Int,
        status: PhotoModerationStatus,
        key: String
    ): ProfilePhoto =
        ProfilePhoto(
            id = UUID.randomUUID(),
            profileId = profileId,
            storageProvider = PhotoStorageProvider.S3,
            storageBucket = "test-bucket",
            storageKey = key,
            position = position,
            validationStatus = PhotoValidationStatus.VALIDATED,
            moderationStatus = status
        )

    private fun anyProfile(): Profile {
        Mockito.any(Profile::class.java)
        return Profile(
            userId = UUID.randomUUID(),
            displayName = "Any Profile",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = mutableSetOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR"
        )
    }

    private fun anyStoredObject(): StoredObject {
        Mockito.any(StoredObject::class.java)
        return StoredObject(
            bucket = "test-bucket",
            key = "any-key",
            contentType = "application/octet-stream",
            sizeBytes = 0
        )
    }

    private fun anyPhotoStorageProvider(): PhotoStorageProvider {
        Mockito.any(PhotoStorageProvider::class.java)
        return PhotoStorageProvider.S3
    }

    private fun anyOffsetDateTime(): OffsetDateTime {
        Mockito.any(OffsetDateTime::class.java)
        return OffsetDateTime.now()
    }

    private class NoOpTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()

        override fun doBegin(
            transaction: Any,
            definition: org.springframework.transaction.TransactionDefinition
        ) = Unit

        override fun doCommit(status: DefaultTransactionStatus) = Unit

        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }
}
