package com.reals.backend.integration.controller

import com.reals.backend.config.filter.RequestCorrelationIdFilter
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class InfrastructureControllerIntegrationTest : ControllerIT() {

    @Test
    fun `request correlation id is echoed in response`() {
        val requestId = "test-request-id"

        mockMvc.perform(
            get("/api/ping")
                .header(RequestCorrelationIdFilter.REQUEST_ID_HEADER, requestId)
        )
            .andExpect(status().isOk)
            .andExpect(header().string(RequestCorrelationIdFilter.REQUEST_ID_HEADER, requestId))
    }

    @Test
    fun `request correlation id is generated when absent`() {
        mockMvc.perform(get("/api/ping"))
            .andExpect(status().isOk)
            .andExpect(header().exists(RequestCorrelationIdFilter.REQUEST_ID_HEADER))
    }

    @Test
    fun `actuator health is available without authentication`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("UP")))
    }

    @Test
    fun `actuator probes are available without authentication`() {
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("UP")))

        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("UP")))
    }

    @Test
    fun `actuator info denies admin pending verified App Check support`() {
        val user = userService.createUser("info-user-${UUID.randomUUID()}@example.com")
        val admin = userService.createUser("info-admin-${UUID.randomUUID()}@example.com")

        mockMvc.perform(get("/actuator/info"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            get("/actuator/info")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isForbidden)

        mockMvc.perform(
            get("/actuator/info")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    @Test
    fun `actuator metrics deny admin pending verified App Check support`() {
        val user = userService.createUser("metrics-user-${UUID.randomUUID()}@example.com")
        val admin = userService.createUser("metrics-admin-${UUID.randomUUID()}@example.com")

        mockMvc.perform(get("/actuator/metrics"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            get("/actuator/metrics")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isForbidden)

        mockMvc.perform(
            get("/actuator/metrics")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(header().string("Cache-Control", "no-store"))

        mockMvc.perform(
            get("/actuator/metrics/jvm.memory.used")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isForbidden)

        mockMvc.perform(
            get("/actuator/metrics/jvm.memory.used")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(header().string("Cache-Control", "no-store"))
    }
}
