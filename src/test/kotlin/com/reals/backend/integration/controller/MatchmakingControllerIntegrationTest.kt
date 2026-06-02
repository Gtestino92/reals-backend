package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.LookingForGender
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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
    }
}
