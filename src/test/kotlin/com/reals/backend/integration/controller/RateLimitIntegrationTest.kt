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
        "security.rate-limit.provision-refill-period-seconds=60",
        "security.rate-limit.safety-report-capacity=1",
        "security.rate-limit.safety-report-refill-tokens=1",
        "security.rate-limit.safety-report-refill-period-seconds=60"
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

    @Test
    fun `safety report endpoint returns too many requests after limit is exceeded`() {
        val token = "safety-report-rate-limit-${UUID.randomUUID()}"
        val userId = UUID.randomUUID()

        mockMvc.perform(
            post("/api/safety/reports")
                .header("Authorization", "Bearer $token")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"details":"missing required fields"}""")
        )
            .andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/api/safety/reports")
                .header("Authorization", "Bearer $token")
                .with(authenticatedAs(userId))
                .contentType(jsonContentType)
                .content("""{"details":"missing required fields"}""")
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.code", equalTo("RATE_LIMIT_EXCEEDED")))
    }

    @Test
    fun `safety report rate limit is per authenticated user behind same ip`() {
        val userAId = UUID.randomUUID()
        val userBId = UUID.randomUUID()

        mockMvc.perform(
            post("/api/safety/reports")
                .header("Authorization", "Bearer token-a-1")
                .with(authenticatedAs(userAId))
                .contentType(jsonContentType)
                .content("""{"details":"missing required fields"}""")
        )
            .andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/api/safety/reports")
                .header("Authorization", "Bearer token-a-2")
                .with(authenticatedAs(userAId))
                .contentType(jsonContentType)
                .content("""{"details":"missing required fields"}""")
        )
            .andExpect(status().isTooManyRequests)

        mockMvc.perform(
            post("/api/safety/reports")
                .header("Authorization", "Bearer token-b-1")
                .with(authenticatedAs(userBId))
                .contentType(jsonContentType)
                .content("""{"details":"missing required fields"}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `admin safety report endpoint does not use safety report specific rate limit`() {
        val token = "admin-safety-report-rate-limit-${UUID.randomUUID()}"
        val adminUserId = UUID.randomUUID()

        mockMvc.perform(
            post("/api/admin/safety-reports")
                .header("Authorization", "Bearer $token")
                .with(authenticatedAsAdmin(adminUserId))
                .contentType(jsonContentType)
                .content("""{"details":"missing required fields"}""")
        )
            .andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/api/admin/safety-reports")
                .header("Authorization", "Bearer $token")
                .with(authenticatedAsAdmin(adminUserId))
                .contentType(jsonContentType)
                .content("""{"details":"missing required fields"}""")
        )
            .andExpect(status().isBadRequest)
    }
}
