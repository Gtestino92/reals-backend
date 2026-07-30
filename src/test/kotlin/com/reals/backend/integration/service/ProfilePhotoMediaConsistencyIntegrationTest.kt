package com.reals.backend.integration.service

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.MediaCleanupTaskStatus
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.StoredObject
import com.reals.backend.integration.BaseIT
import com.reals.backend.repository.MediaCleanupTaskRepository
import com.reals.backend.service.MediaCleanupProcessor
import com.reals.backend.service.S3StorageService
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.ObjectStorageException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.imageio.ImageIO

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProfilePhotoMediaConsistencyIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var mediaCleanupTaskRepository: MediaCleanupTaskRepository

    @MockitoBean
    private lateinit var storageService: S3StorageService

    @MockitoSpyBean
    private lateinit var mediaCleanupProcessor: MediaCleanupProcessor

    @BeforeEach
    fun cleanMediaCleanupState() {
        mediaCleanupTaskRepository.deleteAll()
        Mockito.reset(storageService)
    }

    @Test
    fun `successful upload persists photo and removes guard task`() {
        val profileId = createDraftProfile()
        val storedObject = storedObject("new-upload.jpg")
        stubStorageUpload(storedObject)

        val photo = profileService.uploadPhoto(
            profileId = profileId,
            position = 1,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            bytes = jpegBytes()
        )

        assertEquals(storedObject.key, photo.storageKey)
        assertEquals(storedObject.key, profilePhotoRepository.findById(photo.id).orElseThrow().storageKey)
        assertTrue(mediaCleanupTaskRepository.findAll().isEmpty())
    }

    @Test
    fun `upload finalization failure attempts cleanup without dangling reference`() {
        val profileId = createDraftProfile()
        val storedObject = storedObject("orphan-after-race.jpg")
        stubStorageUpload(storedObject)
        Mockito.`when`(
            storageService.uploadProfilePhoto(
                anyUuid(),
                anyUuid(),
                eqString(MediaType.IMAGE_JPEG_VALUE),
                anyByteArray()
            )
        ).thenAnswer {
            profilePhotoRepository.saveAndFlush(
                ProfilePhoto(
                    profileId = profileId,
                    storageProvider = PhotoStorageProvider.S3,
                    storageBucket = "test-bucket",
                    storageKey = "concurrent-position-winner.jpg",
                    position = 1,
                    isPersonPhoto = true,
                    isFullBody = true,
                    validationStatus = PhotoValidationStatus.VALIDATED,
                    moderationStatus = PhotoModerationStatus.APPROVED
                )
            )
            storedObject
        }

        assertThrows<DomainConflictException> {
            profileService.uploadPhoto(
                profileId = profileId,
                position = 1,
                contentType = MediaType.IMAGE_JPEG_VALUE,
                bytes = jpegBytes()
            )
        }

        assertFalse(profilePhotoRepository.findByProfileId(profileId).any { it.storageKey == storedObject.key })
        assertTrue(mediaCleanupTaskRepository.findAll().isEmpty())
        Mockito.verify(storageService).deleteObject(storedObject.bucket, storedObject.key)
    }

    @Test
    fun `replacement uses new key and failed old-object delete leaves retryable task`() {
        val profileId = createDraftProfile()
        val oldPhoto = profilePhotoRepository.saveAndFlush(
            ProfilePhoto(
                profileId = profileId,
                storageProvider = PhotoStorageProvider.S3,
                storageBucket = "test-bucket",
                storageKey = "old-photo.jpg",
                position = 1,
                isPersonPhoto = true,
                isFullBody = true,
                validationStatus = PhotoValidationStatus.VALIDATED,
                moderationStatus = PhotoModerationStatus.APPROVED
            )
        )
        val newObject = storedObject("new-replacement.jpg")
        stubStorageUpload(newObject)
        Mockito.doThrow(ObjectStorageException("delete failed"))
            .`when`(storageService).deleteObject("test-bucket", oldPhoto.storageKey)

        val replaced = profileService.replacePhoto(
            profileId = profileId,
            photoId = oldPhoto.id,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            bytes = jpegBytes()
        )

        assertEquals(oldPhoto.id, replaced.id)
        assertEquals(newObject.key, profilePhotoRepository.findById(oldPhoto.id).orElseThrow().storageKey)
        assertNotEquals(oldPhoto.storageKey, newObject.key)
        val task = mediaCleanupTaskRepository.findAll().single()
        assertEquals(oldPhoto.storageKey, task.objectKey)
        assertEquals(1, task.attemptCount)
        assertEquals(MediaCleanupTaskStatus.PENDING, task.status)
    }

    @Test
    fun `replacement returns when immediate old cleanup throws after persistence`() {
        val profileId = createDraftProfile()
        val oldPhoto = profilePhotoRepository.saveAndFlush(
            ProfilePhoto(
                profileId = profileId,
                storageProvider = PhotoStorageProvider.S3,
                storageBucket = "test-bucket",
                storageKey = "old-after-persist-failure.jpg",
                position = 1,
                isPersonPhoto = true,
                isFullBody = true,
                validationStatus = PhotoValidationStatus.VALIDATED,
                moderationStatus = PhotoModerationStatus.APPROVED
            )
        )
        val newObject = storedObject("new-after-persist-failure.jpg")
        stubStorageUpload(newObject)
        Mockito.doThrow(RuntimeException("unexpected cleanup failure"))
            .`when`(mediaCleanupProcessor).processTask(anyUuid(), anyOffsetDateTime())

        val replaced = profileService.replacePhoto(
            profileId = profileId,
            photoId = oldPhoto.id,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            bytes = jpegBytes()
        )

        assertEquals(oldPhoto.id, replaced.id)
        assertEquals(newObject.key, profilePhotoRepository.findById(oldPhoto.id).orElseThrow().storageKey)
        Mockito.verify(storageService, Mockito.never()).deleteObject(newObject.bucket, newObject.key)

        val task = mediaCleanupTaskRepository.findAll().single()
        assertEquals(oldPhoto.storageKey, task.objectKey)
        assertEquals(0, task.attemptCount)
        assertEquals(MediaCleanupTaskStatus.PENDING, task.status)
    }

    @Test
    fun `delete commits database state when storage deletion fails and leaves retryable task`() {
        val profileId = createDraftProfile()
        val photo = profilePhotoRepository.saveAndFlush(
            ProfilePhoto(
                profileId = profileId,
                storageProvider = PhotoStorageProvider.S3,
                storageBucket = "test-bucket",
                storageKey = "delete-failure.jpg",
                position = 1,
                isPersonPhoto = true,
                isFullBody = true,
                validationStatus = PhotoValidationStatus.VALIDATED,
                moderationStatus = PhotoModerationStatus.APPROVED
            )
        )
        Mockito.doThrow(ObjectStorageException("delete failed"))
            .`when`(storageService).deleteObject("test-bucket", photo.storageKey)

        profileService.deletePhoto(profileId, photo.id)

        assertFalse(profilePhotoRepository.existsById(photo.id))
        val task = mediaCleanupTaskRepository.findAll().single()
        assertEquals(photo.storageKey, task.objectKey)
        assertEquals(1, task.attemptCount)
        assertEquals(MediaCleanupTaskStatus.PENDING, task.status)
    }

    private fun createDraftProfile(): UUID {
        val user = userService.createUser("media-${UUID.randomUUID()}@example.com")
        return profileService.createProfile(
            userId = user.id,
            displayName = "Media Test",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        ).id
    }

    private fun storedObject(key: String): StoredObject =
        StoredObject(
            bucket = "test-bucket",
            key = key,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = jpegBytes().size.toLong()
        )

    private fun stubStorageUpload(storedObject: StoredObject) {
        Mockito.`when`(storageService.profilePhotoBucket()).thenReturn(storedObject.bucket)
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
        Mockito.`when`(storageService.getReadUrl(storedObject.bucket, storedObject.key))
            .thenReturn("http://localhost:9000/test-bucket/${storedObject.key}")
    }

    private fun jpegBytes(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }

    private fun anyUuid(): UUID {
        Mockito.any(UUID::class.java)
        return UUID.randomUUID()
    }

    private fun anyByteArray(): ByteArray {
        Mockito.any(ByteArray::class.java)
        return byteArrayOf()
    }

    private fun eqString(value: String): String {
        Mockito.eq(value)
        return value
    }

    private fun anyOffsetDateTime(): OffsetDateTime {
        Mockito.any(OffsetDateTime::class.java)
        return OffsetDateTime.now()
    }
}
