package com.reals.backend.service

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.s3.ProfilePhotoStorageProperties
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.authenticity.NoopProfileAuthenticityVerificationProvider
import com.reals.backend.service.authenticity.ProfileAuthenticityPolicy
import com.reals.backend.service.authenticity.ProfileAuthenticityPolicyProperties
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationService
import com.reals.backend.service.photo.ProfilePhotoAnalysisService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

class ProfileServiceTest {

    @Test
    fun `failed prod none-provider authenticity verification does not persist verified state or audit event`() {
        val profile = Profile(
            id = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            displayName = "Identity Test",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = mutableSetOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            authenticityVerificationStatus = ProfileAuthenticityVerificationStatus.NOT_STARTED,
            authenticityVerified = false,
            updatedAt = OffsetDateTime.parse("2026-07-09T12:00:00Z")
        )
        val profileRepository = Mockito.mock(ProfileRepository::class.java)
        val auditEventService = Mockito.mock(AuditEventService::class.java)
        Mockito.`when`(profileRepository.findById(profile.id))
            .thenReturn(Optional.of(profile))

        val service = profileService(
            profileRepository = profileRepository,
            auditEventService = auditEventService
        )

        val exception = assertThrows<DomainConflictException> {
            service.verifyProfileAuthenticity(profile.id)
        }

        assertEquals(DomainErrorCode.AUTHENTICITY_VERIFICATION_NOT_CONFIGURED, exception.code)
        assertEquals(ProfileAuthenticityVerificationStatus.NOT_STARTED, profile.authenticityVerificationStatus)
        assertEquals(false, profile.authenticityVerified)
        assertEquals(OffsetDateTime.parse("2026-07-09T12:00:00Z"), profile.updatedAt)
        Mockito.verify(profileRepository, Mockito.never()).save(Mockito.any(Profile::class.java))
        Mockito.verifyNoInteractions(auditEventService)
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
        Mockito.`when`(profilePhotoRepository.findById(photo.id))
            .thenReturn(Optional.of(photo))
        Mockito.`when`(profileRepository.save(anyProfile()))
            .thenAnswer { invocation -> invocation.getArgument<Profile>(0) }

        val service = profileService(
            profileRepository = profileRepository,
            profilePhotoRepository = profilePhotoRepository,
            auditEventService = auditEventService
        )

        val saved = service.deletePhoto(profile.id, photo.id)

        assertEquals(ProfileAuthenticityVerificationStatus.NOT_STARTED, saved.authenticityVerificationStatus)
        assertEquals(false, saved.authenticityVerified)

        val eventTypes = Mockito.mockingDetails(auditEventService)
            .invocations
            .map { invocation -> invocation.arguments[0] as AuditEventType }
        assertEquals(listOf(AuditEventType.PROFILE_PHOTO_DELETED), eventTypes)
    }

    private fun profileService(
        profileRepository: ProfileRepository,
        auditEventService: AuditEventService,
        profilePhotoRepository: ProfilePhotoRepository = Mockito.mock(ProfilePhotoRepository::class.java),
        storageService: S3StorageService = Mockito.mock(S3StorageService::class.java)
    ): ProfileService =
        ProfileService(
            profileRepository = profileRepository,
            profilePhotoRepository = profilePhotoRepository,
            profilePhotoValidationService = Mockito.mock(ProfilePhotoValidationService::class.java),
            profilePhotoAnalysisService = Mockito.mock(ProfilePhotoAnalysisService::class.java),
            profileAuthenticityVerificationService = ProfileAuthenticityVerificationService(
                provider = NoopProfileAuthenticityVerificationProvider(),
                policy = ProfileAuthenticityPolicy(ProfileAuthenticityPolicyProperties()),
                environmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles("prod"),
                failOnProviderError = false
            ),
            storageService = storageService,
            profilePhotoStorageProperties = ProfilePhotoStorageProperties(),
            auditEventService = auditEventService,
            homeStateInvalidationService = Mockito.mock(HomeStateInvalidationService::class.java),
            countryReferenceService = CountryReferenceService(),
            maxPhotoCount = 9,
            requiredPhotoCount = 9,
            minPersonPhotos = 3,
            minFullBodyPhotos = 1,
            persistRejectedPhotos = false,
            requireModerationApprovalForActivation = true,
            requireAuthenticityVerificationForActivation = false
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
}
