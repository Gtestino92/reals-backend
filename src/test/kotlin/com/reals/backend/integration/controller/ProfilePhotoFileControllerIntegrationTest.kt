package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.StoredObject
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.S3StorageService
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

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
                url = "http://localhost:9000/test-bucket/users/$userId/profile-photos/uploaded.jpg",
                contentType = MediaType.IMAGE_JPEG_VALUE,
                sizeBytes = 4
            )
        )

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(jpegFile(name = "file"))
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.url", equalTo("http://localhost:9000/test-bucket/users/$userId/profile-photos/uploaded.jpg")))
            .andExpect(jsonPath("$.position", equalTo(1)))
            .andExpect(jsonPath("$.isPersonPhoto", equalTo(true)))
            .andExpect(jsonPath("$.isFullBody", equalTo(false)))
            .andExpect(jsonPath("$.validationStatus", equalTo("VALIDATED")))
    }

    @Test
    fun `replace photo file uploads replacement and deletes previous object`() {
        val userId = createDraftProfile()
        val oldObject = StoredObject(
            bucket = "test-bucket",
            key = "users/$userId/profile-photos/old.jpg",
            url = "http://localhost:9000/test-bucket/users/$userId/profile-photos/old.jpg",
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = 4
        )
        val newObject = StoredObject(
            bucket = "test-bucket",
            key = "users/$userId/profile-photos/new.jpg",
            url = "http://localhost:9000/test-bucket/users/$userId/profile-photos/new.jpg",
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = 4
        )
        stubStorageUploads(oldObject, newObject)

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
            .andExpect(jsonPath("$.url", equalTo(newObject.url)))
            .andExpect(jsonPath("$.position", equalTo(1)))
            .andExpect(jsonPath("$.validationStatus", equalTo("VALIDATED")))

        Mockito.verify(storageService).delete(oldObject.key)
    }

    @Test
    fun `delete photo by id removes storage object and returns updated profile`() {
        val userId = createDraftProfile()
        val storedObject = StoredObject(
            bucket = "test-bucket",
            key = "users/$userId/profile-photos/delete-me.jpg",
            url = "http://localhost:9000/test-bucket/users/$userId/profile-photos/delete-me.jpg",
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

        Mockito.verify(storageService).delete(storedObject.key)
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
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("INVALID_PROFILE_PHOTO")))

        Mockito.verifyNoInteractions(storageService)
    }

    private fun createDraftProfile(): UUID {
        val user = userService.createUser("photo-file-${UUID.randomUUID()}@example.com")

        profileService.createProfile(
            userId = user.id,
            displayName = "Photo File",
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

        return user.id
    }

    private fun jpegFile(name: String): MockMultipartFile =
        MockMultipartFile(
            name,
            "photo.jpg",
            MediaType.IMAGE_JPEG_VALUE,
            byteArrayOf(1, 2, 3, 4)
        )

    private fun stubStorageUpload(storedObject: StoredObject) {
        stubStorageUploads(storedObject)
    }

    private fun stubStorageUploads(vararg storedObjects: StoredObject) {
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
                .thenReturn(storedObject.url)
        }
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
