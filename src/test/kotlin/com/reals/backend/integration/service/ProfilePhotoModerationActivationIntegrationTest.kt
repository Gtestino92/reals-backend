package com.reals.backend.integration.service

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhoto
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
        "profile.photos.require-moderation-approval-for-activation=true"
    ]
)
class ProfilePhotoModerationActivationIntegrationTest : BaseIT() {

    @Test
    fun `activation succeeds when strict moderation is enabled and all photos are approved`() {
        val profileId = createDraftProfileWithPhotos(
            firstPhotoModerationStatus = PhotoModerationStatus.APPROVED
        )

        val activated = profileService.activateProfile(profileId)

        assertEquals(com.reals.backend.domain.ProfileStatus.ACTIVE, activated.status)
    }

    @Test
    fun `activation fails when strict moderation is enabled and any photo is not approved`() {
        listOf(
            PhotoModerationStatus.PENDING,
            PhotoModerationStatus.REJECTED,
            PhotoModerationStatus.NEEDS_REVIEW
        ).forEach { status ->
            val profileId = createDraftProfileWithPhotos(
                firstPhotoModerationStatus = status
            )

            val exception = assertThrows<DomainConflictException> {
                profileService.activateProfile(profileId)
            }

            assertEquals(DomainErrorCode.PROFILE_PHOTO_MODERATION_NOT_APPROVED, exception.code)
        }
    }

    private fun createDraftProfileWithPhotos(
        firstPhotoModerationStatus: PhotoModerationStatus
    ): UUID {
        val user = userService.createUser("photo-moderation-activation-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = "Moderation Activation",
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
                    moderationStatus = if (index == 0) {
                        firstPhotoModerationStatus
                    } else {
                        PhotoModerationStatus.APPROVED
                    }
                )
            )
        }

        return profile.id
    }
}
