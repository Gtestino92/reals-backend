package com.reals.backend.integration.controller

import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ConnectionControllerIntegrationTest : ControllerIT() {

    @Test
    fun `submit proposal list returns created proposals`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        val body = mapOf(
            "proposedDateTimes" to listOf(
                slot.toString(),
                slot.plusHours(1).toString()
            )
        )

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].userId", equalTo(setup.userAId.toString())))
            .andExpect(jsonPath("$[0].preferenceOrder", equalTo(1)))
            .andExpect(jsonPath("$[1].preferenceOrder", equalTo(2)))
    }

    @Test
    fun `matching proposal lists confirm negotiation over http`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        val body = mapOf("proposedDateTimes" to listOf(slot.toString()))

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}/negotiation")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo(NegotiationStatus.CONFIRMED.name)))
            .andExpect(jsonPath("$.confirmedDateTime").exists())
    }

    @Test
    fun `user can accept partner proposal without own proposal over http`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        val body = mapOf("proposedDateTimes" to listOf(slot.toString()))

        val proposalId =
            objectMapper.readTree(
                mockMvc.perform(
                    post("/api/connections/${setup.connectionId}/proposals")
                        .with(authenticatedAs(setup.userAId))
                        .contentType(jsonContentType)
                        .content(jsonBody(body))
                )
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
        )[0]["id"].asString()

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals/$proposalId/acceptance")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo(NegotiationStatus.CONFIRMED.name)))
            .andExpect(jsonPath("$.confirmedDateTime").exists())
    }

    @Test
    fun `non participant cannot get connection`() {
        val setup = createConnectionInSchedulingPhase()
        val stranger = userService.createUser("connection-stranger-${java.util.UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}")
                .with(authenticatedAs(stranger.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error", equalTo("Forbidden")))
    }

    @Test
    fun `proposal validation conflict is returned as error json`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        val body = mapOf(
            "proposedDateTimes" to listOf(
                slot.toString(),
                slot.plusHours(1).toString(),
                slot.plusHours(2).toString(),
                slot.plusHours(3).toString()
            )
        )

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/proposals")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error", equalTo("Conflict")))
            .andExpect(jsonPath("$.message", equalTo("Proposal list must contain between 1 and 3 date/times")))
    }
}
