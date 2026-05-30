package com.reals.backend.integration.controller

import com.reals.backend.config.filter.RequestCorrelationIdFilter
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

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
    fun `actuator info is available without authentication`() {
        mockMvc.perform(get("/actuator/info"))
            .andExpect(status().isOk)
    }
}
