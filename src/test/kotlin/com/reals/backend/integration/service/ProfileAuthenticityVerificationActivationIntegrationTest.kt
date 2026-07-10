package com.reals.backend.integration.service

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.context.TestPropertySource
import java.time.LocalDate
import java.util.UUID

@TestPropertySource(
    properties = [
        "profile.authenticity-verification.require-for-activation=true"
    ]
)
class ProfileAuthenticityVerificationActivationIntegrationTest : BaseIT() {

    @Test
    fun `activation succeeds when strict authenticity verification is enabled and profile is verified`() {
        val profileId = createDraftProfileWithPhotos(ProfileAuthenticityVerificationStatus.VERIFIED)

        val activated = profileService.activateProfile(profileId)

        assertEquals(ProfileStatus.ACTIVE, activated.status)
    }

    @Test
    fun `activation fails when strict authenticity verification is enabled and profile is not verified`() {
        listOf(
            ProfileAuthenticityVerificationStatus.NOT_STARTED,
            ProfileAuthenticityVerificationStatus.PENDING,
            ProfileAuthenticityVerificationStatus.REJECTED,
            ProfileAuthenticityVerificationStatus.NEEDS_REVIEW,
            ProfileAuthenticityVerificationStatus.STALE
        ).forEach { status ->
            val profileId = createDraftProfileWithPhotos(status)

            val exception = assertThrows<DomainConflictException> {
                profileService.activateProfile(profileId)
            }

            assertEquals(DomainErrorCode.PROFILE_AUTHENTICITY_VERIFICATION_REQUIRED, exception.code)
        }
    }

    private fun createDraftProfileWithPhotos(
        authenticityVerificationStatus: ProfileAuthenticityVerificationStatus
    ): UUID {
        val user = userService.createUser("identity-activation-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = "Identity Activation",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            country = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        profile.authenticityVerificationStatus = authenticityVerificationStatus
        profile.authenticityVerified =
            authenticityVerificationStatus == ProfileAuthenticityVerificationStatus.VERIFIED
        profileRepository.save(profile)

        repeat(4) { index ->
            profilePhotoRepository.save(
                ProfilePhoto(
                    profileId = profile.id,
                    storageProvider = PhotoStorageProvider.S3,
                    storageBucket = "reals-profile-photos-test",
                    storageKey = "users/${user.id}/profile-photos/${profile.id}-${index + 1}.jpg",
                    position = index + 1,
                    isPersonPhoto = index == 0,
                    isFullBody = index == 0,
                    validationStatus = PhotoValidationStatus.VALIDATED,
                    moderationStatus = PhotoModerationStatus.APPROVED
                )
            )
        }

        return profile.id
    }
}
