package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.Penalty
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class MatchmakingControllerIntegrationTest : ControllerIT() {

    @Test
    fun `enqueue requires current search location`() {
        val userId = createActiveProfile(
            email = "queue-location-${UUID.randomUUID()}@example.com",
            displayName = "Queue Location",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )

        mockMvc.perform(
            post("/api/matchmaking/queue")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "latitude": -34.6037,
                      "longitude": -58.3816,
                      "accuracyMeters": 50
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId", equalTo(userId.toString())))
            .andExpect(jsonPath("$.inQueue", equalTo(true)))
    }

    @Test
    fun `enqueue is idempotent and refreshes search location`() {
        val userId = createActiveProfile(
            email = "queue-location-refresh-http-${UUID.randomUUID()}@example.com",
            displayName = "Queue Location Refresh HTTP",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )

        mockMvc.perform(
            post("/api/matchmaking/queue")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "latitude": -34.6037,
                      "longitude": -58.3816,
                      "accuracyMeters": 50
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId", equalTo(userId.toString())))
            .andExpect(jsonPath("$.inQueue", equalTo(true)))

        assertEquals(1L, matchmakingQueueRepository.count())

        mockMvc.perform(
            post("/api/matchmaking/queue")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "latitude": -31.4201,
                      "longitude": -64.1888,
                      "accuracyMeters": 25
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId", equalTo(userId.toString())))
            .andExpect(jsonPath("$.inQueue", equalTo(true)))

        val queueEntry = matchmakingQueueRepository.findByUserId(userId)
            ?: error("Expected user to remain queued")

        assertEquals(1L, matchmakingQueueRepository.count())
        assertEquals(-31.4201, queueEntry.latitude)
        assertEquals(-64.1888, queueEntry.longitude)
        assertEquals(25, queueEntry.accuracyMeters)
    }

    @Test
    fun `enqueue rejects invalid search location`() {
        val userId = createActiveProfile(
            email = "queue-location-invalid-${UUID.randomUUID()}@example.com",
            displayName = "Queue Location Invalid",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )

        mockMvc.perform(
            post("/api/matchmaking/queue")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "latitude": -120.0,
                      "longitude": -58.3816,
                      "accuracyMeters": 50
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
    }

    @Test
    fun `enqueue without profile returns stable error code`() {
        val user = userService.createUser("queue-no-profile-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/matchmaking/queue")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(validQueueBody())
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("PROFILE_REQUIRED")))
    }

    @Test
    fun `enqueue with draft profile returns stable error code`() {
        val user = userService.createUser("queue-draft-profile-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Draft Queue Profile",
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

        mockMvc.perform(
            post("/api/matchmaking/queue")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(validQueueBody())
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("PROFILE_NOT_ACTIVE")))
    }

    @Test
    fun `enqueue with active penalty returns stable error code`() {
        val userId = createActiveProfile(
            email = "queue-active-penalty-${UUID.randomUUID()}@example.com",
            displayName = "Queue Active Penalty",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )
        penaltyRepository.save(
            Penalty(
                userId = userId,
                reason = "Integration test penalty",
                expiresAt = OffsetDateTime.now().plusHours(1)
            )
        )

        mockMvc.perform(
            post("/api/matchmaking/queue")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content(validQueueBody())
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("ACTIVE_PENALTY")))
    }

    private fun validQueueBody(): String =
        """
        {
          "latitude": -34.6037,
          "longitude": -58.3816,
          "accuracyMeters": 50
        }
        """.trimIndent()
}
