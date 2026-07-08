package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.IdentityVerificationStatus
import com.reals.backend.domain.Intention
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.identity.IdentityVerificationProvider
import com.reals.backend.service.identity.IdentityVerificationRequest
import com.reals.backend.service.identity.IdentityVerificationResult
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

class ProfileIdentityVerificationIntegrationTest : ControllerIT() {

    @MockitoBean
    private lateinit var identityVerificationProvider: IdentityVerificationProvider

    @Test
    fun `provider rejected result updates identity status and compatibility boolean`() {
        val userId = createDraftProfile()
        stubIdentityResult(IdentityVerificationStatus.REJECTED)

        mockMvc.perform(
            post("/api/me/profile/identity-verification")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.identityVerified", equalTo(false)))
            .andExpect(jsonPath("$.identityVerificationStatus", equalTo("REJECTED")))

        val profile = profileService.findByUserId(userId) ?: error("Expected profile")
        assertEquals(false, profile.identityVerified)
        assertEquals(IdentityVerificationStatus.REJECTED, profile.identityVerificationStatus)
    }

    @Test
    fun `provider needs review result updates identity status and compatibility boolean`() {
        val userId = createDraftProfile()
        stubIdentityResult(IdentityVerificationStatus.NEEDS_REVIEW)

        mockMvc.perform(
            post("/api/me/profile/identity-verification")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.identityVerified", equalTo(false)))
            .andExpect(jsonPath("$.identityVerificationStatus", equalTo("NEEDS_REVIEW")))
    }

    @Test
    fun `provider pending result updates identity status and compatibility boolean`() {
        val userId = createDraftProfile()
        stubIdentityResult(IdentityVerificationStatus.PENDING)

        mockMvc.perform(
            post("/api/me/profile/identity-verification")
                .with(authenticatedAs(userId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.identityVerified", equalTo(false)))
            .andExpect(jsonPath("$.identityVerificationStatus", equalTo("PENDING")))
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
            country = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        return user.id
    }

    private fun stubIdentityResult(status: IdentityVerificationStatus) {
        Mockito.`when`(identityVerificationProvider.verify(anyIdentityRequest()))
            .thenReturn(
                IdentityVerificationResult(
                    status = status,
                    provider = "test"
                )
            )
    }

    private fun anyIdentityRequest(): IdentityVerificationRequest {
        any(IdentityVerificationRequest::class.java)
        return IdentityVerificationRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            displayName = "Any",
            birthDate = LocalDate.of(1995, 1, 1)
        )
    }
}
