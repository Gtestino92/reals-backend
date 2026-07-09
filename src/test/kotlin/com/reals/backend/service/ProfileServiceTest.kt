package com.reals.backend.service

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.s3.ProfilePhotoStorageProperties
import com.reals.backend.domain.Gender
import com.reals.backend.domain.IdentityVerificationStatus
import com.reals.backend.domain.Intention
import com.reals.backend.domain.Profile
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.identity.IdentityVerificationService
import com.reals.backend.service.identity.NoopIdentityVerificationProvider
import com.reals.backend.service.photo.ProfilePhotoModerationService
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
    fun `failed prod none-provider identity verification does not persist verified state or audit event`() {
        val profile = Profile(
            id = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            displayName = "Identity Test",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = mutableSetOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            country = "AR",
            identityVerificationStatus = IdentityVerificationStatus.NOT_STARTED,
            identityVerified = false,
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
            service.verifyIdentity(profile.id)
        }

        assertEquals(DomainErrorCode.IDENTITY_VERIFICATION_NOT_CONFIGURED, exception.code)
        assertEquals(IdentityVerificationStatus.NOT_STARTED, profile.identityVerificationStatus)
        assertEquals(false, profile.identityVerified)
        assertEquals(OffsetDateTime.parse("2026-07-09T12:00:00Z"), profile.updatedAt)
        Mockito.verify(profileRepository, Mockito.never()).save(Mockito.any(Profile::class.java))
        Mockito.verifyNoInteractions(auditEventService)
    }

    private fun profileService(
        profileRepository: ProfileRepository,
        auditEventService: AuditEventService
    ): ProfileService =
        ProfileService(
            profileRepository = profileRepository,
            profilePhotoRepository = Mockito.mock(ProfilePhotoRepository::class.java),
            profilePhotoValidationService = Mockito.mock(ProfilePhotoValidationService::class.java),
            profilePhotoModerationService = Mockito.mock(ProfilePhotoModerationService::class.java),
            identityVerificationService = IdentityVerificationService(
                provider = NoopIdentityVerificationProvider(
                    EnvironmentExposurePolicy.forActiveProfiles("prod")
                ),
                failOnProviderError = false
            ),
            storageService = Mockito.mock(S3StorageService::class.java),
            profilePhotoStorageProperties = ProfilePhotoStorageProperties(),
            auditEventService = auditEventService,
            homeStateInvalidationService = Mockito.mock(HomeStateInvalidationService::class.java),
            maxPhotoCount = 9,
            requiredPhotoCount = 9,
            minPersonPhotos = 3,
            minFullBodyPhotos = 1,
            persistRejectedPhotos = false,
            requireModerationApprovalForActivation = true,
            requireIdentityVerificationForActivation = false
        )
}
