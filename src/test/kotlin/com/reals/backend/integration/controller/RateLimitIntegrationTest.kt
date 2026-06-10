package com.reals.backend.integration.controller

import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@TestPropertySource(
    properties = [
        "security.rate-limit.enabled=true",
        "security.rate-limit.provision-capacity=1",
        "security.rate-limit.provision-refill-tokens=1",
        "security.rate-limit.provision-refill-period-seconds=60"
    ]
)
class RateLimitIntegrationTest : ControllerIT() {

    @Test
    fun `provision endpoint returns too many requests after limit is exceeded`() {
        val firebaseUid = "rate-limit-${UUID.randomUUID()}"
        val email = "$firebaseUid@example.com"

        mockMvc.perform(
            post("/api/me/provision")
                .with(authenticatedWithFirebase(firebaseUid, email))
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/me/provision")
                .with(authenticatedWithFirebase(firebaseUid, email))
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.code", equalTo("RATE_LIMIT_EXCEEDED")))
    }
}
