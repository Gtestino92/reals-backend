package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.StoredObject
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.S3StorageService
import com.reals.backend.service.photo.PhotoModerationProvider
import com.reals.backend.service.photo.PhotoModerationRequest
import com.reals.backend.service.photo.PhotoModerationResult
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO

class ProfilePhotoModerationIntegrationTest : ControllerIT() {

    @MockitoBean
    private lateinit var storageService: S3StorageService

    @MockitoBean
    private lateinit var moderationProvider: PhotoModerationProvider

    @Test
    fun `upload rejected by moderation does not store or persist photo`() {
        val userId = createDraftProfile()

        stubModeration(
            PhotoModerationResult(
                status = PhotoModerationStatus.REJECTED,
                provider = "test",
                reason = "unsafe content"
            )
        )

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(jpegFile(name = "file"))
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("PROFILE_PHOTO_REJECTED")))

        val profile = profileService.findByUserId(userId) ?: error("Expected profile")
        assertEquals(0, profilePhotoRepository.countByProfileId(profile.id))
        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `replace updates moderation status from provider result`() {
        val userId = createDraftProfile()
        val oldObject = StoredObject(
            bucket = "test-bucket",
            key = "users/$userId/profile-photos/old.jpg",
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = jpegBytes().size.toLong()
        )
        val newObject = StoredObject(
            bucket = "test-bucket",
            key = "users/$userId/profile-photos/new.jpg",
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = jpegBytes().size.toLong()
        )
        stubStorageUploads(oldObject, newObject)
        Mockito.`when`(moderationProvider.moderate(anyModerationRequest()))
            .thenReturn(
                PhotoModerationResult(
                    status = PhotoModerationStatus.APPROVED,
                    provider = "test"
                ),
                PhotoModerationResult(
                    status = PhotoModerationStatus.NEEDS_REVIEW,
                    provider = "test"
                )
            )

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(jpegFile(name = "file"))
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.moderationStatus", equalTo("APPROVED")))

        val profile = profileService.findByUserId(userId) ?: error("Expected profile")
        val photoId = profilePhotoRepository.findByProfileId(profile.id).single().id

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
            .andExpect(jsonPath("$.moderationStatus", equalTo("NEEDS_REVIEW")))

        val updated = profilePhotoRepository.findById(photoId).orElseThrow()
        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, updated.moderationStatus)
        Mockito.verify(storageService).delete(oldObject.key)
    }

    @Test
    fun `technical invalid upload does not call moderation provider or storage`() {
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

        Mockito.verifyNoInteractions(moderationProvider)
        Mockito.verifyNoInteractions(storageService)
    }

    private fun createDraftProfile(): UUID {
        val user = userService.createUser("photo-moderation-${UUID.randomUUID()}@example.com")

        profileService.createProfile(
            userId = user.id,
            displayName = "Photo Moderation",
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

    private fun stubModeration(result: PhotoModerationResult) {
        Mockito.`when`(moderationProvider.moderate(anyModerationRequest()))
            .thenReturn(result)
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
                .thenReturn("http://localhost:9000/test-bucket/${storedObject.key}")
        }
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

    private fun anyModerationRequest(): PhotoModerationRequest {
        any(PhotoModerationRequest::class.java)
        return PhotoModerationRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            photoId = UUID.randomUUID(),
            contentType = MediaType.IMAGE_JPEG_VALUE,
            bytes = byteArrayOf()
        )
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
