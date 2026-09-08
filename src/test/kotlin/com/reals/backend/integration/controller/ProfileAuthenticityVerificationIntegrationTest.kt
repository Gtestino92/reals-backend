package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.authenticity.ProfileAuthenticityPhotoComparison
import com.reals.backend.service.authenticity.ProfileAuthenticityPhotoComparisonOutcome
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationProvider
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationProviderResult
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationRequest
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationSignals
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

class ProfileAuthenticityVerificationIntegrationTest : ControllerIT() {

    @MockitoBean
    private lateinit var profileAuthenticityVerificationProvider: ProfileAuthenticityVerificationProvider

    @Test
    fun `provider signals that need review update authenticity status and compatibility boolean`() {
        val userId = createDraftProfile()
        stubLiveReferenceNotAccepted()

        mockMvc.perform(
            post("/api/me/profile/authenticity-verification")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authenticityVerified", equalTo(false)))
            .andExpect(jsonPath("$.authenticityVerificationStatus", equalTo("NEEDS_REVIEW")))

        val profile = profileService.findByUserId(userId) ?: error("Expected profile")
        assertEquals(false, profile.authenticityVerified)
        assertEquals(ProfileAuthenticityVerificationStatus.NEEDS_REVIEW, profile.authenticityVerificationStatus)
    }

    @Test
    fun `provider matched signals update authenticity status and compatibility boolean`() {
        val userId = createDraftProfile()
        val profile = profileService.findByUserId(userId) ?: error("Expected profile")
        savePhoto(profile.id, position = 1, isPersonPhoto = true, PhotoValidationStatus.VALIDATED)
        savePhoto(profile.id, position = 2, isPersonPhoto = true, PhotoValidationStatus.VALIDATED)
        savePhoto(profile.id, position = 3, isPersonPhoto = true, PhotoValidationStatus.VALIDATED)
        stubAllCandidatePhotosMatched()

        mockMvc.perform(
            post("/api/me/profile/authenticity-verification")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authenticityVerified", equalTo(true)))
            .andExpect(jsonPath("$.authenticityVerificationStatus", equalTo("VERIFIED")))
    }

    @Test
    fun `provider request contains only current validated person photos sorted by position`() {
        val userId = createDraftProfile()
        val profile = profileService.findByUserId(userId) ?: error("Expected profile")
        val third = savePhoto(profile.id, position = 3, isPersonPhoto = true, PhotoValidationStatus.VALIDATED)
        savePhoto(profile.id, position = 1, isPersonPhoto = false, PhotoValidationStatus.VALIDATED)
        val second = savePhoto(profile.id, position = 2, isPersonPhoto = true, PhotoValidationStatus.VALIDATED)
        savePhoto(profile.id, position = 4, isPersonPhoto = true, PhotoValidationStatus.FAILED)
        val fifth = savePhoto(profile.id, position = 5, isPersonPhoto = true, PhotoValidationStatus.VALIDATED)
        stubAllCandidatePhotosMatched()

        mockMvc.perform(
            post("/api/me/profile/authenticity-verification")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authenticityVerified", equalTo(true)))
            .andExpect(jsonPath("$.authenticityVerificationStatus", equalTo("VERIFIED")))

        val captor = ArgumentCaptor.forClass(ProfileAuthenticityVerificationRequest::class.java)
        Mockito.verify(profileAuthenticityVerificationProvider).verify(captureAuthenticityRequest(captor))
        val request = captor.value
        assertEquals(userId, request.userId)
        assertEquals(profile.id, request.profileId)
        assertEquals(listOf(second.id, third.id, fifth.id), request.personPhotos.map { it.photoId })
        assertEquals(listOf(second.storageKey, third.storageKey, fifth.storageKey), request.personPhotos.map { it.storageKey })
        assertEquals(listOf(second.version, third.version, fifth.version), request.personPhotos.map { it.photoVersion })
    }

    @Test
    fun `provider request does not expose legal identity or profile metadata inputs`() {
        val fields = ProfileAuthenticityVerificationRequest::class.java.declaredFields.map { it.name }

        assertEquals(setOf("userId", "profileId", "personPhotos"), fields.toSet())
        assertFalse(fields.contains("displayName"))
        assertFalse(fields.contains("birthDate"))
    }

    private fun createDraftProfile(): UUID {
        val user = userService.createUser("identity-provider-${UUID.randomUUID()}@example.com")

        profileService.createProfile(
            userId = user.id,
            displayName = "Identity Provider",
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

    private fun stubLiveReferenceNotAccepted() {
        Mockito.`when`(profileAuthenticityVerificationProvider.verify(anyAuthenticityRequest()))
            .thenReturn(
                ProfileAuthenticityVerificationProviderResult.Success(
                    ProfileAuthenticityVerificationSignals(
                        provider = "test",
                        liveReferenceAccepted = false,
                        photoComparisons = emptyList()
                    )
                )
            )
    }

    private fun stubAllCandidatePhotosMatched() {
        Mockito.`when`(profileAuthenticityVerificationProvider.verify(anyAuthenticityRequest()))
            .thenAnswer { invocation ->
                val request = invocation.arguments[0] as ProfileAuthenticityVerificationRequest
                ProfileAuthenticityVerificationProviderResult.Success(
                    ProfileAuthenticityVerificationSignals(
                        provider = "test",
                        liveReferenceAccepted = true,
                        photoComparisons = request.personPhotos.map {
                            ProfileAuthenticityPhotoComparison(
                                photoId = it.photoId,
                                outcome = ProfileAuthenticityPhotoComparisonOutcome.MATCHED
                            )
                        }
                    )
                )
            }
    }

    private fun anyAuthenticityRequest(): ProfileAuthenticityVerificationRequest {
        any(ProfileAuthenticityVerificationRequest::class.java)
        return ProfileAuthenticityVerificationRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            personPhotos = emptyList()
        )
    }

    private fun captureAuthenticityRequest(
        captor: ArgumentCaptor<ProfileAuthenticityVerificationRequest>
    ): ProfileAuthenticityVerificationRequest {
        captor.capture()
        return ProfileAuthenticityVerificationRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            personPhotos = emptyList()
        )
    }

    private fun savePhoto(
        profileId: UUID,
        position: Int,
        isPersonPhoto: Boolean,
        validationStatus: PhotoValidationStatus
    ): ProfilePhoto =
        profilePhotoRepository.save(
            ProfilePhoto(
                profileId = profileId,
                storageProvider = PhotoStorageProvider.S3,
                storageBucket = "reals-media-test",
                storageKey = "authenticity/profile/$profileId/$position.jpg",
                position = position,
                isPersonPhoto = isPersonPhoto,
                isFullBody = false,
                validationStatus = validationStatus,
                moderationStatus = PhotoModerationStatus.APPROVED
            )
        )
}
