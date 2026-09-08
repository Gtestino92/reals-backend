package com.reals.backend.service

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.authenticity.NoopProfileAuthenticityVerificationProvider
import com.reals.backend.service.authenticity.ProfileAuthenticityPolicy
import com.reals.backend.service.authenticity.ProfileAuthenticityPolicyProperties
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationService
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.photo.ProfilePhotoService
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

    private fun profileService(
        profileRepository: ProfileRepository,
        auditEventService: AuditEventService
    ): ProfileService =
        ProfileService(
            profileRepository = profileRepository,
            profilePhotoService = Mockito.mock(ProfilePhotoService::class.java),
            profileAuthenticityVerificationService = ProfileAuthenticityVerificationService(
                provider = NoopProfileAuthenticityVerificationProvider(),
                policy = ProfileAuthenticityPolicy(ProfileAuthenticityPolicyProperties()),
                environmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles("prod"),
                failOnProviderError = false
            ),
            auditEventService = auditEventService,
            homeStateInvalidationService = Mockito.mock(HomeStateInvalidationService::class.java),
            countryReferenceService = CountryReferenceService(),
            requireAuthenticityVerificationForActivation = false
        )
}
