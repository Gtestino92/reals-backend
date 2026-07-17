package com.reals.backend.integration.service

import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.StoredObject
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.S3StorageService
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.photo.PhotoPlacement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProfileAuthenticityPhotoMutationIntegrationTest : BaseIT() {

    @MockitoBean
    private lateinit var storageService: S3StorageService

    @Test
    fun `upload after verified makes authenticity stale and false`() {
        val profileId = createProfileWithAuthenticity(ProfileAuthenticityVerificationStatus.VERIFIED)
        stubStorageUpload("authenticity/upload/$profileId.jpg")

        profileService.uploadPhoto(
            profileId = profileId,
            position = 1,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            bytes = jpegBytes()
        )

        assertAuthenticity(profileId, ProfileAuthenticityVerificationStatus.STALE, false)
    }

    @Test
    fun `replace after verified makes authenticity stale and false`() {
        val profileId = createProfileWithAuthenticity(ProfileAuthenticityVerificationStatus.VERIFIED)
        val photo = savePhoto(profileId, position = 1)
        stubStorageUpload("authenticity/replace/$profileId.jpg")

        profileService.replacePhoto(
            profileId = profileId,
            photoId = photo.id,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            bytes = jpegBytes()
        )

        assertAuthenticity(profileId, ProfileAuthenticityVerificationStatus.STALE, false)
    }

    @Test
    fun `delete after verified makes authenticity stale and false and records audit transition`() {
        val profileId = createProfileWithAuthenticity(ProfileAuthenticityVerificationStatus.VERIFIED)
        val photo = savePhoto(profileId, position = 1)

        profileService.deletePhoto(profileId, photo.id)

        assertAuthenticity(profileId, ProfileAuthenticityVerificationStatus.STALE, false)
        val event = auditEventRepository.findAll().single {
            it.eventType == AuditEventType.PROFILE_AUTHENTICITY_VERIFICATION_UPDATED &&
                it.aggregateId == profileId
        }
        assertTrue(event.metadataJson!!.contains("VERIFIED"))
        assertTrue(event.metadataJson!!.contains("STALE"))
        assertTrue(event.metadataJson!!.contains("PROFILE_PHOTO_MUTATED"))
        assertTrue(event.metadataJson!!.contains("authenticityVerified"))
    }

    @Test
    fun `photo mutation after pending rejected or needs review makes authenticity stale`() {
        listOf(
            ProfileAuthenticityVerificationStatus.PENDING,
            ProfileAuthenticityVerificationStatus.REJECTED,
            ProfileAuthenticityVerificationStatus.NEEDS_REVIEW
        ).forEach { status ->
            val profileId = createProfileWithAuthenticity(status)
            val photo = savePhoto(profileId, position = 1)

            profileService.deletePhoto(profileId, photo.id)

            assertAuthenticity(profileId, ProfileAuthenticityVerificationStatus.STALE, false)
        }
    }

    @Test
    fun `photo mutation from not started remains not started`() {
        val profileId = createProfileWithAuthenticity(ProfileAuthenticityVerificationStatus.NOT_STARTED)
        val photo = savePhoto(profileId, position = 1)

        profileService.deletePhoto(profileId, photo.id)

        assertAuthenticity(profileId, ProfileAuthenticityVerificationStatus.NOT_STARTED, false)
    }

    @Test
    fun `photo mutation from stale remains stale`() {
        val profileId = createProfileWithAuthenticity(ProfileAuthenticityVerificationStatus.STALE)
        val photo = savePhoto(profileId, position = 1)

        profileService.deletePhoto(profileId, photo.id)

        assertAuthenticity(profileId, ProfileAuthenticityVerificationStatus.STALE, false)
    }

    @Test
    fun `reorder does not invalidate verified authenticity`() {
        val profileId = createProfileWithAuthenticity(ProfileAuthenticityVerificationStatus.VERIFIED)
        val first = savePhoto(profileId, position = 1)
        val second = savePhoto(profileId, position = 2)

        profileService.reorderPhotos(
            profileId = profileId,
            placements = listOf(
                PhotoPlacement(first.id, 2),
                PhotoPlacement(second.id, 1)
            )
        )

        assertAuthenticity(profileId, ProfileAuthenticityVerificationStatus.VERIFIED, true)
    }

    @Test
    fun `failed upload before successful mutation does not invalidate verified authenticity`() {
        val profileId = createProfileWithAuthenticity(ProfileAuthenticityVerificationStatus.VERIFIED)

        assertThrows<DomainBadRequestException> {
            profileService.uploadPhoto(
                profileId = profileId,
                position = 1,
                contentType = "image/gif",
                bytes = byteArrayOf(1, 2, 3)
            )
        }

        assertAuthenticity(profileId, ProfileAuthenticityVerificationStatus.VERIFIED, true)
    }

    private fun createProfileWithAuthenticity(
        status: ProfileAuthenticityVerificationStatus
    ): UUID {
        val user = userService.createUser("authenticity-mutation-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = "Authenticity Mutation",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )
        profile.authenticityVerificationStatus = status
        profile.authenticityVerified = status == ProfileAuthenticityVerificationStatus.VERIFIED
        profileRepository.save(profile)
        return profile.id
    }

    private fun assertAuthenticity(
        profileId: UUID,
        status: ProfileAuthenticityVerificationStatus,
        verified: Boolean
    ) {
        val profile = profileService.findByIdOrThrow(profileId)
        assertEquals(status, profile.authenticityVerificationStatus)
        assertEquals(verified, profile.authenticityVerified)
        if (status == ProfileAuthenticityVerificationStatus.STALE) {
            assertFalse(profile.authenticityVerified)
        }
    }

    private fun savePhoto(
        profileId: UUID,
        position: Int
    ): ProfilePhoto =
        profilePhotoRepository.save(
            ProfilePhoto(
                profileId = profileId,
                storageProvider = PhotoStorageProvider.S3,
                storageBucket = "reals-profile-photos-test",
                storageKey = "authenticity/profile/$profileId/$position.jpg",
                position = position,
                isPersonPhoto = true,
                isFullBody = false,
                validationStatus = PhotoValidationStatus.VALIDATED,
                moderationStatus = PhotoModerationStatus.APPROVED
            )
        )

    private fun stubStorageUpload(key: String) {
        val storedObject = StoredObject(
            bucket = "test-bucket",
            key = key,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = jpegBytes().size.toLong()
        )
        Mockito.`when`(
            storageService.profilePhotoBucket()
        ).thenReturn(storedObject.bucket)

        Mockito.`when`(
            storageService.profilePhotoObjectKey(
                anyUuid(),
                anyUuid(),
                eqString(MediaType.IMAGE_JPEG_VALUE)
            )
        ).thenReturn(storedObject.key)

        Mockito.`when`(
            storageService.uploadProfilePhoto(
                anyUuid(),
                anyUuid(),
                eqString(MediaType.IMAGE_JPEG_VALUE),
                anyByteArray()
            )
        ).thenReturn(storedObject)
    }

    private fun jpegBytes(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }

    private fun anyUuid(): UUID {
        any(UUID::class.java)
        return UUID.randomUUID()
    }

    private fun anyByteArray(): ByteArray {
        any(ByteArray::class.java)
        return byteArrayOf()
    }

    private fun eqString(value: String): String {
        eq(value)
        return value
    }
}
