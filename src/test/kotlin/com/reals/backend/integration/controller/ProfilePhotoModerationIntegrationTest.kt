package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.StoredObject
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.S3StorageService
import com.reals.backend.service.photo.ProfilePhotoAnalysisProvider
import com.reals.backend.service.photo.ProfilePhotoAnalysisProviderResult
import com.reals.backend.service.photo.ProfilePhotoAnalysisRequest
import com.reals.backend.service.photo.ProfilePhotoAnalysisSignals
import com.reals.backend.service.photo.ProfilePhotoModerationSignals
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProfilePhotoModerationIntegrationTest : ControllerIT() {

    @MockitoBean
    private lateinit var storageService: S3StorageService

    @MockitoBean
    private lateinit var analysisProvider: ProfilePhotoAnalysisProvider

    @Test
    fun `upload rejected by moderation does not store or persist photo`() {
        val userId = createDraftProfile()

        stubAnalysis(
            realFaceCount = 1,
            moderation = moderationSignals(sexualExplicit = 0.80)
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
        Mockito.verify(analysisProvider, Mockito.times(1)).analyze(anyAnalysisRequest())
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
        Mockito.`when`(analysisProvider.analyze(anyAnalysisRequest()))
            .thenReturn(
                successAnalysis(
                    realFaceCount = 1,
                    moderation = moderationSignals()
                ),
                successAnalysis(
                    realFaceCount = 1,
                    moderation = moderationSignals(sexualExplicit = 0.50)
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
        Mockito.verify(storageService).deleteObject(oldObject.bucket, oldObject.key)
        Mockito.verify(analysisProvider, Mockito.times(2)).analyze(anyAnalysisRequest())
    }

    @Test
    fun `technical invalid upload does not call analysis provider or storage`() {
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

        Mockito.verifyNoInteractions(analysisProvider)
        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `unverified user cannot upload and costly services are not called`() {
        val userId = createDraftProfile()

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(jpegFile(name = "file"))
                .param("position", "1")
                .with(authenticatedWithContext(userId, email = "photo@example.com", emailVerified = false))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("EMAIL_NOT_VERIFIED")))

        val profile = profileService.findByUserId(userId) ?: error("Expected profile")
        assertEquals(0, profilePhotoRepository.countByProfileId(profile.id))
        Mockito.verifyNoInteractions(analysisProvider)
        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `unverified user cannot replace and existing photo is unchanged`() {
        val userId = createDraftProfile()
        val oldObject = StoredObject(
            bucket = "test-bucket",
            key = "users/$userId/profile-photos/old.jpg",
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = jpegBytes().size.toLong()
        )
        stubStorageUploads(oldObject)
        stubAnalysis(
            realFaceCount = 1,
            moderation = moderationSignals()
        )

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(jpegFile(name = "file"))
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isCreated)

        Mockito.reset(storageService, analysisProvider)
        val profile = profileService.findByUserId(userId) ?: error("Expected profile")
        val photo = profilePhotoRepository.findByProfileId(profile.id).single()

        mockMvc.perform(
            multipart("/api/me/profile/photos/${photo.id}/file")
                .file(jpegFile(name = "file"))
                .with { request ->
                    request.method = "PUT"
                    request
                }
                .with(authenticatedWithContext(userId, email = "photo@example.com", emailVerified = false))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("EMAIL_NOT_VERIFIED")))

        assertEquals(oldObject.key, profilePhotoRepository.findById(photo.id).orElseThrow().storageKey)
        Mockito.verifyNoInteractions(analysisProvider)
        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `successful analysis with no real face and approved moderation persists derived state`() {
        val userId = createDraftProfile()
        val storedObject = StoredObject(
            bucket = "test-bucket",
            key = "users/$userId/profile-photos/no-face.jpg",
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = jpegBytes().size.toLong()
        )
        stubStorageUploads(storedObject)
        stubAnalysis(
            realFaceCount = 0,
            moderation = moderationSignals()
        )

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(jpegFile(name = "file"))
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus", equalTo("VALIDATED")))
            .andExpect(jsonPath("$.isPersonPhoto", equalTo(false)))
            .andExpect(jsonPath("$.isFullBody", equalTo(false)))
            .andExpect(jsonPath("$.moderationStatus", equalTo("APPROVED")))

        val profile = profileService.findByUserId(userId) ?: error("Expected profile")
        val photo = profilePhotoRepository.findByProfileId(profile.id).single()
        assertEquals(PhotoValidationStatus.VALIDATED, photo.validationStatus)
        assertEquals(false, photo.isPersonPhoto)
        assertEquals(false, photo.isFullBody)
        assertEquals(PhotoModerationStatus.APPROVED, photo.moderationStatus)
        Mockito.verify(analysisProvider, Mockito.times(1)).analyze(anyAnalysisRequest())
    }

    @Test
    fun `successful analysis with real face and ambiguous moderation persists needs review`() {
        val userId = createDraftProfile()
        val storedObject = StoredObject(
            bucket = "test-bucket",
            key = "users/$userId/profile-photos/face-review.jpg",
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = jpegBytes().size.toLong()
        )
        stubStorageUploads(storedObject)
        stubAnalysis(
            realFaceCount = 1,
            moderation = moderationSignals(sexualSuggestive = 0.80)
        )

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(jpegFile(name = "file"))
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus", equalTo("VALIDATED")))
            .andExpect(jsonPath("$.isPersonPhoto", equalTo(true)))
            .andExpect(jsonPath("$.isFullBody", equalTo(false)))
            .andExpect(jsonPath("$.moderationStatus", equalTo("NEEDS_REVIEW")))

        val profile = profileService.findByUserId(userId) ?: error("Expected profile")
        val photo = profilePhotoRepository.findByProfileId(profile.id).single()
        assertEquals(PhotoValidationStatus.VALIDATED, photo.validationStatus)
        assertEquals(true, photo.isPersonPhoto)
        assertEquals(false, photo.isFullBody)
        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, photo.moderationStatus)
        Mockito.verify(analysisProvider, Mockito.times(1)).analyze(anyAnalysisRequest())
    }

    @Test
    fun `analysis and storage receive identical normalized JPEG bytes`() {
        val userId = createDraftProfile()
        val sourceBytes = pngBytes()
        val storedObject = StoredObject(
            bucket = "test-bucket",
            key = "users/$userId/profile-photos/normalized.jpg",
            contentType = MediaType.IMAGE_JPEG_VALUE,
            sizeBytes = 100
        )
        stubStorageUploads(storedObject)
        stubAnalysis(
            realFaceCount = 1,
            moderation = moderationSignals()
        )

        mockMvc.perform(
            multipart("/api/me/profile/photos")
                .file(MockMultipartFile("file", "photo.png", MediaType.IMAGE_PNG_VALUE, sourceBytes))
                .param("position", "1")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isCreated)

        val analysisCaptor = ArgumentCaptor.forClass(ProfilePhotoAnalysisRequest::class.java)
        Mockito.verify(analysisProvider).analyze(captureAnalysisRequest(analysisCaptor))
        val storageBytesCaptor = ArgumentCaptor.forClass(ByteArray::class.java)
        Mockito.verify(storageService).uploadProfilePhoto(
            anyUuid(),
            anyUuid(),
            eqString(MediaType.IMAGE_JPEG_VALUE),
            captureByteArray(storageBytesCaptor)
        )

        assertEquals(MediaType.IMAGE_JPEG_VALUE, analysisCaptor.value.contentType)
        assertEquals(storageBytesCaptor.value.toList(), analysisCaptor.value.bytes.toList())
        assertNotEquals(sourceBytes.toList(), analysisCaptor.value.bytes.toList())
    }

    private fun createDraftProfile(): UUID {
        val user = userService.createUser("photo-moderation-${UUID.randomUUID()}@example.com")

        profileService.createProfile(
            userId = user.id,
            displayName = "Photo Moderation",
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

    private fun stubAnalysis(
        realFaceCount: Int,
        moderation: ProfilePhotoModerationSignals
    ) {
        Mockito.`when`(analysisProvider.analyze(anyAnalysisRequest()))
            .thenReturn(successAnalysis(realFaceCount, moderation))
    }

    private fun successAnalysis(
        realFaceCount: Int,
        moderation: ProfilePhotoModerationSignals
    ): ProfilePhotoAnalysisProviderResult =
        ProfilePhotoAnalysisProviderResult.Success(
            provider = "test",
            signals = ProfilePhotoAnalysisSignals(
                provider = "test",
                realFaceCount = realFaceCount,
                moderation = moderation
            )
        )

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
            Mockito.`when`(storageService.getReadUrl(storedObject.bucket, storedObject.key))
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

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }

    private fun anyAnalysisRequest(): ProfilePhotoAnalysisRequest {
        any(ProfilePhotoAnalysisRequest::class.java)
        return ProfilePhotoAnalysisRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            photoId = UUID.randomUUID(),
            contentType = MediaType.IMAGE_JPEG_VALUE,
            bytes = byteArrayOf()
        )
    }

    private fun moderationSignals(
        sexualExplicit: Double = 0.0,
        sexualSuggestive: Double = 0.0,
        violenceOrThreat: Double = 0.0,
        gore: Double = 0.0,
        hateOrExtremism: Double = 0.0
    ): ProfilePhotoModerationSignals =
        ProfilePhotoModerationSignals(
            sexualExplicit = sexualExplicit,
            sexualSuggestive = sexualSuggestive,
            violenceOrThreat = violenceOrThreat,
            gore = gore,
            hateOrExtremism = hateOrExtremism
        )

    private fun anyUuid(): UUID {
        any(UUID::class.java)
        return UUID.randomUUID()
    }

    private fun anyByteArray(): ByteArray {
        any(ByteArray::class.java)
        return byteArrayOf()
    }

    private fun captureByteArray(captor: ArgumentCaptor<ByteArray>): ByteArray {
        captor.capture()
        return byteArrayOf()
    }

    private fun captureAnalysisRequest(
        captor: ArgumentCaptor<ProfilePhotoAnalysisRequest>
    ): ProfilePhotoAnalysisRequest {
        captor.capture()
        return ProfilePhotoAnalysisRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            photoId = UUID.randomUUID(),
            contentType = MediaType.IMAGE_JPEG_VALUE,
            bytes = byteArrayOf()
        )
    }

    private fun eqString(value: String): String {
        eq(value)
        return value
    }
}
