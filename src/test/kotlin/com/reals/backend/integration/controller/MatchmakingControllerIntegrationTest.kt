package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.Penalty
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            lookingForGenders = setOf(Gender.MALE)
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
            lookingForGenders = setOf(Gender.MALE)
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
            lookingForGenders = setOf(Gender.MALE)
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
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
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
            lookingForGenders = setOf(Gender.MALE)
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

    @Test
    fun `matchmaking accepts mutual gender preference sets`() {
        val userA = createActiveProfile(
            email = "queue-compatible-a-${UUID.randomUUID()}@example.com",
            displayName = "Compatible A",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE, Gender.NON_BINARY)
        )
        val userB = createActiveProfile(
            email = "queue-compatible-b-${UUID.randomUUID()}@example.com",
            displayName = "Compatible B",
            gender = Gender.NON_BINARY,
            lookingForGenders = setOf(Gender.MALE)
        )
        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)

        val result = matchmakingProcessorService.process(maxPairsPerRun = 1)

        assertEquals(1, result.matchesCreated)
        org.junit.jupiter.api.Assertions.assertTrue(matchExistsForUsers(userA, userB))
    }

    @Test
    fun `matchmaking rejects when candidate gender is not accepted`() {
        val userA = createActiveProfile(
            email = "queue-candidate-not-accepted-a-${UUID.randomUUID()}@example.com",
            displayName = "Candidate Not Accepted A",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        val userB = createActiveProfile(
            email = "queue-candidate-not-accepted-b-${UUID.randomUUID()}@example.com",
            displayName = "Candidate Not Accepted B",
            gender = Gender.NON_BINARY,
            lookingForGenders = setOf(Gender.MALE)
        )
        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)

        val result = matchmakingProcessorService.process(maxPairsPerRun = 1)

        assertEquals(0, result.matchesCreated)
        assertFalse(matchExistsForUsers(userA, userB))
    }

    @Test
    fun `matchmaking requires gender compatibility to be mutual`() {
        val userA = createActiveProfile(
            email = "queue-not-mutual-a-${UUID.randomUUID()}@example.com",
            displayName = "Not Mutual A",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.NON_BINARY)
        )
        val userB = createActiveProfile(
            email = "queue-not-mutual-b-${UUID.randomUUID()}@example.com",
            displayName = "Not Mutual B",
            gender = Gender.NON_BINARY,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)

        val result = matchmakingProcessorService.process(maxPairsPerRun = 1)

        assertEquals(0, result.matchesCreated)
        assertFalse(matchExistsForUsers(userA, userB))
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
