package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.StoredObject
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.S3StorageService
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProfilePhotoFileControllerIntegrationTest : ControllerIT() {

    @MockitoBean
    private lateinit var storageService: S3StorageService

    @Test
    fun `upload photo stores multipart file and returns validated photo`() {
        val userId = createDraftProfile()
        stubStorageUpload(
            StoredObject(
                bucket = "test-bucket",
                key = "users/$userId/profile-photos/uploaded.jpg",
                contentType = MediaType.IMAGE_JPEG_VALUE,
                sizeBytes = 4
            )
        )
        val expectedUrl = readUrlFor("users/$userId/profile-photos/uploaded.jpg")

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(jpegFile(name = "file"))
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.url", equalTo(expectedUrl)))
            .andExpect(jsonPath("$.position", equalTo(1)))
            .andExpect(jsonPath("$.isPersonPhoto", equalTo(true)))
            .andExpect(jsonPath("$.isFullBody", equalTo(true)))
            .andExpect(jsonPath("$.validationStatus", equalTo("VALIDATED")))
            .andExpect(jsonPath("$.moderationStatus", equalTo("APPROVED")))

        val profile = profileService.findByUserId(userId)!!
        val savedPhoto = profileService.getPhotos(profile.id).single()
        assertEquals("users/$userId/profile-photos/uploaded.jpg", savedPhoto.storageKey)
        assertEquals("APPROVED", savedPhoto.moderationStatus.name)

        mockMvc.perform(
            get("/api/me/profile/photos")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].url", equalTo(expectedUrl)))
            .andExpect(jsonPath("$[0].moderationStatus", equalTo("APPROVED")))
    }

    @Test
    fun `replace photo file uploads replacement and deletes previous object`() {
        val userId = createDraftProfile()
        val oldObject = StoredObject(
            bucket = "test-bucket",
            key = "users/$userId/profile-photos/old.jpg",
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = 4
        )
        val newObject = StoredObject(
            bucket = "test-bucket",
            key = "users/$userId/profile-photos/new.jpg",
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = 4
        )
        stubStorageUploads(oldObject, newObject)
        val expectedUrl = readUrlFor(newObject.key)

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(jpegFile(name = "file"))
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isCreated)

        val profile = profileService.findByUserId(userId)!!
        val photoId = profileService.getPhotos(profile.id).single().id

        mockMvc.perform(
            multipart("/api/me/profile/photos/$photoId/file")
                .file(jpegFile(name = "file"))
                .with { request ->
                    request.method = "PUT"
                    request
                }
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(photoId.toString())))
            .andExpect(jsonPath("$.url", equalTo(expectedUrl)))
            .andExpect(jsonPath("$.position", equalTo(1)))
            .andExpect(jsonPath("$.isPersonPhoto", equalTo(true)))
            .andExpect(jsonPath("$.isFullBody", equalTo(true)))
            .andExpect(jsonPath("$.validationStatus", equalTo("VALIDATED")))
            .andExpect(jsonPath("$.moderationStatus", equalTo("APPROVED")))

        assertEquals(newObject.key, profileService.getPhotos(profile.id).single().storageKey)
        Mockito.verify(storageService).deleteObject(oldObject.bucket, oldObject.key)
    }

    @Test
    fun `delete photo by id removes storage object and returns updated profile`() {
        val userId = createDraftProfile()
        val storedObject = StoredObject(
            bucket = "test-bucket",
            key = "users/$userId/profile-photos/delete-me.jpg",
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = 4
        )
        stubStorageUpload(storedObject)

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(jpegFile(name = "file"))
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isCreated)

        val profile = profileService.findByUserId(userId)!!
        val photoId = profileService.getPhotos(profile.id).single().id

        mockMvc.perform(
            delete("/api/me/profile/photos/$photoId")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.photoCount", equalTo(0)))
            .andExpect(jsonPath("$.status", equalTo("DRAFT")))

        Mockito.verify(storageService).deleteObject(storedObject.bucket, storedObject.key)
    }

    @Test
    fun `upload photo rejects unsupported content type before storage call`() {
        val userId = createDraftProfile()
        val file = MockMultipartFile(
            "file",
            "photo.gif",
            "image/gif",
            byteArrayOf(1, 2, 3)
        )

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(file)
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("INVALID_PROFILE_PHOTO")))

        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `upload photo rejects empty file before storage call`() {
        val userId = createDraftProfile()
        val file = MockMultipartFile(
            "file",
            "photo.jpg",
            MediaType.IMAGE_JPEG_VALUE,
            byteArrayOf()
        )

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(file)
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("INVALID_PROFILE_PHOTO")))

        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `upload photo rejects oversized file before storage call`() {
        val userId = createDraftProfile()
        val file = MockMultipartFile(
            "file",
            "photo.jpg",
            MediaType.IMAGE_JPEG_VALUE,
            ByteArray(5 * 1024 * 1024 + 1)
        )

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(file)
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("INVALID_PROFILE_PHOTO")))

        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `upload photo rejects undecodable image before storage call`() {
        val userId = createDraftProfile()
        val file = MockMultipartFile(
            "file",
            "photo.jpg",
            MediaType.IMAGE_JPEG_VALUE,
            byteArrayOf(1, 2, 3, 4)
        )

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(file)
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("INVALID_PROFILE_PHOTO")))

        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `upload photo missing file part returns stable error code`() {
        val userId = createDraftProfile()

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("INVALID_PROFILE_PHOTO")))

        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `upload photo missing position returns stable error code`() {
        val userId = createDraftProfile()

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(jpegFile(name = "file"))
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("PHOTO_POSITION_INVALID")))

        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `upload photo invalid position returns stable error code`() {
        val userId = createDraftProfile()

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(jpegFile(name = "file"))
                .param("position", "0")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("PHOTO_POSITION_INVALID")))

        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `profile activation succeeds after uploading required valid multipart photos`() {
        val userId = createDraftProfile()
        val storedObjects = (1..4).map { position ->
            StoredObject(
                bucket = "test-bucket",
                key = "users/$userId/profile-photos/$position.jpg",
                contentType = MediaType.IMAGE_JPEG_VALUE,
                sizeBytes = jpegBytes().size.toLong()
            )
        }
        stubStorageUploads(*storedObjects.toTypedArray())

        repeat(4) { index ->
            mockMvc.perform(
                multipart("/api/me/profile/photos")
                    .file(jpegFile(name = "file"))
                    .param("position", "${index + 1}")
                    .with(authenticatedAs(userId))
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.validationStatus", equalTo("VALIDATED")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("APPROVED")))
                .andExpect(jsonPath("$.isPersonPhoto", equalTo(true)))
                .andExpect(jsonPath("$.isFullBody", equalTo(true)))
        }

        mockMvc.perform(
            post("/api/me/profile/activation")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
    }

    private fun createDraftProfile(): UUID {
        val user = userService.createUser("photo-file-${UUID.randomUUID()}@example.com")

        profileService.createProfile(
            userId = user.id,
            displayName = "Photo File",
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

        return user.id
    }

    private fun jpegFile(name: String): MockMultipartFile =
        MockMultipartFile(
            name,
            "photo.jpg",
            MediaType.IMAGE_JPEG_VALUE,
            jpegBytes()
        )

    private fun jpegBytes(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }

    private fun stubStorageUpload(storedObject: StoredObject) {
        stubStorageUploads(storedObject)
    }

    private fun stubStorageUploads(vararg storedObjects: StoredObject) {
        Mockito.`when`(
            storageService.profilePhotoBucket()
        ).thenReturn(storedObjects.first().bucket)

        Mockito.`when`(
            storageService.profilePhotoObjectKey(
                anyUuid(),
                anyUuid(),
                eqString(MediaType.IMAGE_JPEG_VALUE)
            )
        ).thenReturn(storedObjects.first().key, *storedObjects.drop(1).map { it.key }.toTypedArray())

        Mockito.`when`(
            storageService.uploadProfilePhoto(
                anyUuid(),
                anyUuid(),
                eqString(MediaType.IMAGE_JPEG_VALUE),
                anyByteArray()
            )
        ).thenReturn(storedObjects.first(), *storedObjects.drop(1).toTypedArray())

        storedObjects.forEach { storedObject ->
            Mockito.`when`(storageService.getReadUrl(storedObject.key))
                .thenReturn(readUrlFor(storedObject.key))
        }
    }

    private fun readUrlFor(key: String): String =
        "http://localhost:9000/test-bucket/$key"

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
