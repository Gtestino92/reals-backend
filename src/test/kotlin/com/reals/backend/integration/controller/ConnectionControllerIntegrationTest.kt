package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime

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
    fun `get second chat materializes active second chat over http`() {
        val setup = createScheduledSecondChatReadyToEnter()

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}/chat")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.chatType", equalTo("SECOND_CHAT")))
            .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
            .andExpect(jsonPath("$.availableAt").exists())
            .andExpect(jsonPath("$.activatedAt").exists())

        assertEquals(
            1,
            chatRepository.findAll().count {
                it.connectionId == setup.connectionId && it.chatType == ChatType.SECOND_CHAT
            }
        )
        assertEquals(
            ConnectionState.SECOND_CHAT,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
    }

    @Test
    fun `get second chat before available time returns conflict`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        schedulingService.addProposal(setup.connectionId, setup.userAId, slot)
        schedulingService.addProposal(setup.connectionId, setup.userBId, slot)

        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = OffsetDateTime.now().plusMinutes(1)
        )

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}/chat")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SECOND_CHAT_NOT_AVAILABLE_YET")))

        assertEquals(
            null,
            chatRepository.findByConnectionIdAndChatType(setup.connectionId, ChatType.SECOND_CHAT)
        )
    }

    @Test
    fun `get second chat after expired scheduled window returns conflict`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()
        schedulingService.addProposal(setup.connectionId, setup.userAId, slot)
        schedulingService.addProposal(setup.connectionId, setup.userBId, slot)

        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = OffsetDateTime.now().minusMinutes(121)
        )

        mockMvc.perform(
            get("/api/connections/${setup.connectionId}/chat")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SECOND_CHAT_EXPIRED")))

        assertEquals(
            null,
            chatRepository.findByConnectionIdAndChatType(setup.connectionId, ChatType.SECOND_CHAT)
        )
    }

    @Test
    fun `user can dismiss read only second chat from home`() {
        val setup = createReadOnlySecondChat()

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat-dismissal")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.dismissed", equalTo(true)))

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))

        assertEquals(
            1,
            connectionHomeDismissalRepository.findAll().count {
                it.userId == setup.userAId && it.connectionId == setup.connectionId
            }
        )
        assertEquals(ChatStatus.EXPIRED, chatRepository.findById(setup.secondChatId).orElseThrow().status)
    }

    @Test
    fun `second chat dismissal is user specific`() {
        val setup = createReadOnlySecondChat()

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat-dismissal")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(1)))
            .andExpect(jsonPath("$.nextSteps[0].type", equalTo("SECOND_CHAT_READ_ONLY")))
            .andExpect(jsonPath("$.nextSteps[0].connectionId", equalTo(setup.connectionId.toString())))
    }

    @Test
    fun `second chat dismissal endpoint is idempotent`() {
        val setup = createReadOnlySecondChat()

        repeat(2) {
            mockMvc.perform(
                post("/api/connections/${setup.connectionId}/second-chat-dismissal")
                    .with(authenticatedAs(setup.userAId))
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.dismissed", equalTo(true)))
        }

        assertEquals(
            1,
            connectionHomeDismissalRepository.findAll().count {
                it.userId == setup.userAId && it.connectionId == setup.connectionId
            }
        )
    }

    @Test
    fun `second chat dismissal rejects actionable second chat`() {
        val setup = createActiveSecondChat()

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat-dismissal")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("DOMAIN_CONFLICT")))

        assertFalse(
            connectionHomeDismissalRepository.existsByUserIdAndConnectionId(
                userId = setup.userAId,
                connectionId = setup.connectionId
            )
        )
    }

    @Test
    fun `second chat dismissal rejects non participant`() {
        val setup = createReadOnlySecondChat()
        val stranger = userService.createUser("dismiss-stranger-${java.util.UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/connections/${setup.connectionId}/second-chat-dismissal")
                .with(authenticatedAs(stranger.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error", equalTo("Forbidden")))
    }

    @Test
    fun `proposal validation bad request is returned with stable error code`() {
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
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("SCHEDULING_INVALID_PROPOSALS")))
            .andExpect(jsonPath("$.error", equalTo("Bad Request")))
    }

    private fun createReadOnlySecondChat() =
        createActiveSecondChat()
            .also {
                chatRepository.updateTimeoutAt(
                    chatId = it.secondChatId,
                    timeoutAt = OffsetDateTime.now().minusSeconds(1)
                )
                chatService.expireSecondChatToReadOnly(it.secondChatId)
            }
}
