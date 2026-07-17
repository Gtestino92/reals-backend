package com.reals.backend.integration.controller

import com.reals.backend.config.filter.RequestCorrelationIdFilter
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.ReadMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class InfrastructureControllerIntegrationTest : ControllerIT() {

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

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
    fun `actuator info is available without authentication`() {
        mockMvc.perform(get("/actuator/info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.app.name", equalTo("reals-backend")))
            .andExpect(jsonPath("$.image").exists())
    }

    @Test
    fun `actuator metrics require admin role`() {
        Timer.builder(ReadMetrics.HOME_LOAD)
            .tag("variant", ReadMetrics.HOME_VARIANT_FULL)
            .tag("outcome", "success")
            .register(meterRegistry)
            .record(java.time.Duration.ofMillis(1))

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
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.names").exists())

        mockMvc.perform(
            get("/actuator/metrics/${ReadMetrics.HOME_LOAD}")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isForbidden)

        mockMvc.perform(
            get("/actuator/metrics/${ReadMetrics.HOME_LOAD}")
                .with(authenticatedAsAdmin(admin.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name", equalTo(ReadMetrics.HOME_LOAD)))
    }
}
