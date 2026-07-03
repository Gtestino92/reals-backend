package com.reals.backend.integration.service

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.service.photo.PhotoPlacement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ProfilePhotoReorderIntegrationTest : BaseIT() {

    @Test
    fun `valid reorder changes positions`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos()

        profileService.reorderPhotos(
            profileId = profileId,
            placements = listOf(
                PhotoPlacement(photos[0].id, 3),
                PhotoPlacement(photos[1].id, 1),
                PhotoPlacement(photos[2].id, 2)
            )
        )

        val positionsById = profileService.getPhotos(profileId).associate { it.id to it.position }
        assertEquals(3, positionsById.getValue(photos[0].id))
        assertEquals(1, positionsById.getValue(photos[1].id))
        assertEquals(2, positionsById.getValue(photos[2].id))
    }

    @Test
    fun `valid reorder with holes preserves holes`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos()

        val reordered = profileService.reorderPhotos(
            profileId = profileId,
            placements = listOf(
                PhotoPlacement(photos[0].id, 9),
                PhotoPlacement(photos[1].id, 1),
                PhotoPlacement(photos[2].id, 4)
            )
        )

        assertEquals(listOf(1, 4, 9), reordered.map { it.position })
    }

    @Test
    fun `swapping positions succeeds without unique constraint failure`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos()

        val reordered = profileService.reorderPhotos(
            profileId = profileId,
            placements = listOf(
                PhotoPlacement(photos[0].id, 2),
                PhotoPlacement(photos[1].id, 1),
                PhotoPlacement(photos[2].id, 3)
            )
        )

        assertEquals(listOf(1, 2, 3), reordered.map { it.position })
        assertEquals(photos[1].id, reordered[0].id)
        assertEquals(photos[0].id, reordered[1].id)
    }

    @Test
    fun `duplicate target positions fail`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos()

        val exception = assertThrows(DomainBadRequestException::class.java) {
            profileService.reorderPhotos(
                profileId = profileId,
                placements = listOf(
                    PhotoPlacement(photos[0].id, 1),
                    PhotoPlacement(photos[1].id, 1),
                    PhotoPlacement(photos[2].id, 3)
                )
            )
        }

        assertEquals(DomainErrorCode.PHOTO_POSITION_OCCUPIED, exception.code)
    }

    @Test
    fun `duplicate photo IDs fail`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos()

        val exception = assertThrows(DomainBadRequestException::class.java) {
            profileService.reorderPhotos(
                profileId = profileId,
                placements = listOf(
                    PhotoPlacement(photos[0].id, 1),
                    PhotoPlacement(photos[0].id, 2),
                    PhotoPlacement(photos[2].id, 3)
                )
            )
        }

        assertEquals(DomainErrorCode.INVALID_PROFILE_PHOTO, exception.code)
    }

    @Test
    fun `position outside user facing range fails`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos()

        val exception = assertThrows(DomainBadRequestException::class.java) {
            profileService.reorderPhotos(
                profileId = profileId,
                placements = listOf(
                    PhotoPlacement(photos[0].id, 10),
                    PhotoPlacement(photos[1].id, 1),
                    PhotoPlacement(photos[2].id, 2)
                )
            )
        }

        assertEquals(DomainErrorCode.PHOTO_POSITION_INVALID, exception.code)
    }

    @Test
    fun `omitting an existing profile photo fails`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos()

        val exception = assertThrows(DomainBadRequestException::class.java) {
            profileService.reorderPhotos(
                profileId = profileId,
                placements = listOf(
                    PhotoPlacement(photos[0].id, 1),
                    PhotoPlacement(photos[1].id, 2)
                )
            )
        }

        assertEquals(DomainErrorCode.INVALID_PROFILE_PHOTO, exception.code)
    }

    @Test
    fun `including a nonexistent photo fails as not found`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos()
        val nonexistentPhotoId = UUID.randomUUID()

        val exception = assertThrows(DomainNotFoundException::class.java) {
            profileService.reorderPhotos(
                profileId = profileId,
                placements = listOf(
                    PhotoPlacement(photos[0].id, 1),
                    PhotoPlacement(photos[1].id, 2),
                    PhotoPlacement(nonexistentPhotoId, 3)
                )
            )
        }

        assertEquals(DomainErrorCode.PROFILE_PHOTO_NOT_FOUND, exception.code)
    }

    @Test
    fun `including a photo from another profile fails as not found`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos(emailPrefix = "owner")
        val (_, _, otherPhotos) = createDraftProfileWithPhotos(emailPrefix = "other")

        val exception = assertThrows(DomainNotFoundException::class.java) {
            profileService.reorderPhotos(
                profileId = profileId,
                placements = listOf(
                    PhotoPlacement(photos[0].id, 1),
                    PhotoPlacement(photos[1].id, 2),
                    PhotoPlacement(otherPhotos[0].id, 3)
                )
            )
        }

        assertEquals(DomainErrorCode.PROFILE_PHOTO_NOT_FOUND, exception.code)
    }

    @Test
    fun `reorder preserves storage validation moderation and semantic fields`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos()
        val before = photos.associateBy { it.id }

        profileService.reorderPhotos(
            profileId = profileId,
            placements = listOf(
                PhotoPlacement(photos[0].id, 3),
                PhotoPlacement(photos[1].id, 1),
                PhotoPlacement(photos[2].id, 2)
            )
        )

        profileService.getPhotos(profileId).forEach { after ->
            val original = before.getValue(after.id)
            assertEquals(original.storageProvider, after.storageProvider)
            assertEquals(original.storageBucket, after.storageBucket)
            assertEquals(original.storageKey, after.storageKey)
            assertEquals(original.validationStatus, after.validationStatus)
            assertEquals(original.moderationStatus, after.moderationStatus)
            assertEquals(original.isPersonPhoto, after.isPersonPhoto)
            assertEquals(original.isFullBody, after.isFullBody)
        }
    }

    @Test
    fun `reorder does not move active profile to draft`() {
        val userId = createActiveProfile(
            email = "active-reorder-${UUID.randomUUID()}@example.com",
            displayName = "Active Reorder",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )
        val profile = profileService.findByUserId(userId) ?: error("Profile not found")
        val photos = profileService.getPhotos(profile.id)

        profileService.reorderPhotos(
            profileId = profile.id,
            placements = photos.mapIndexed { index, photo ->
                PhotoPlacement(photo.id, photos.size - index)
            }
        )

        assertEquals(ProfileStatus.ACTIVE, profileService.findByIdOrThrow(profile.id).status)
    }

    @Test
    fun `reorder preserves all existing photo row IDs`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos()
        val originalIds = photos.map { it.id }.toSet()

        val reordered = profileService.reorderPhotos(
            profileId = profileId,
            placements = listOf(
                PhotoPlacement(photos[0].id, 3),
                PhotoPlacement(photos[1].id, 1),
                PhotoPlacement(photos[2].id, 2)
            )
        )

        assertEquals(originalIds, reordered.map { it.id }.toSet())
    }

    @Test
    fun `failed reorder leaves previous positions unchanged`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos()
        val previousPositionsById = profileService.getPhotos(profileId).associate { it.id to it.position }

        assertThrows(DomainBadRequestException::class.java) {
            profileService.reorderPhotos(
                profileId = profileId,
                placements = listOf(
                    PhotoPlacement(photos[0].id, 1),
                    PhotoPlacement(photos[1].id, 1),
                    PhotoPlacement(photos[2].id, 3)
                )
            )
        }

        assertEquals(previousPositionsById, profileService.getPhotos(profileId).associate { it.id to it.position })
    }

    @Test
    fun `reorder updates only position values`() {
        val (_, profileId, photos) = createDraftProfileWithPhotos()
        val beforePositions = photos.associate { it.id to it.position }

        val reordered = profileService.reorderPhotos(
            profileId = profileId,
            placements = listOf(
                PhotoPlacement(photos[0].id, 3),
                PhotoPlacement(photos[1].id, 1),
                PhotoPlacement(photos[2].id, 2)
            )
        )

        assertNotEquals(beforePositions, reordered.associate { it.id to it.position })
    }

    private fun createDraftProfileWithPhotos(
        emailPrefix: String = "photo-reorder"
    ): Triple<UUID, UUID, List<ProfilePhoto>> {
        val user = userService.createUser("$emailPrefix-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = "Photo Reorder",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN,
            intention = Intention.DATE,
            city = "Buenos Aires",
            country = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        val photos = listOf(
            createPhoto(
                profileId = profile.id,
                userId = user.id,
                position = 1,
                storageKeySuffix = "one",
                isPersonPhoto = true,
                isFullBody = true,
                validationStatus = PhotoValidationStatus.VALIDATED,
                moderationStatus = PhotoModerationStatus.APPROVED
            ),
            createPhoto(
                profileId = profile.id,
                userId = user.id,
                position = 2,
                storageKeySuffix = "two",
                isPersonPhoto = false,
                isFullBody = false,
                validationStatus = PhotoValidationStatus.FAILED,
                moderationStatus = PhotoModerationStatus.NEEDS_REVIEW
            ),
            createPhoto(
                profileId = profile.id,
                userId = user.id,
                position = 3,
                storageKeySuffix = "three",
                isPersonPhoto = true,
                isFullBody = false,
                validationStatus = PhotoValidationStatus.PENDING,
                moderationStatus = PhotoModerationStatus.REJECTED
            )
        )

        return Triple(user.id, profile.id, photos)
    }

    private fun createPhoto(
        profileId: UUID,
        userId: UUID,
        position: Int,
        storageKeySuffix: String,
        isPersonPhoto: Boolean,
        isFullBody: Boolean,
        validationStatus: PhotoValidationStatus,
        moderationStatus: PhotoModerationStatus
    ): ProfilePhoto =
        profilePhotoRepository.save(
            ProfilePhoto(
                profileId = profileId,
                storageProvider = PhotoStorageProvider.S3,
                storageBucket = "reals-profile-photos-test",
                storageKey = "users/$userId/profile-photos/$storageKeySuffix.jpg",
                position = position,
                isPersonPhoto = isPersonPhoto,
                isFullBody = isFullBody,
                validationStatus = validationStatus,
                moderationStatus = moderationStatus
            )
        )
}
