package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatExitRequestStatus
import com.reals.backend.domain.ChatExitRequestType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ChatControllerIntegrationTest : ControllerIT() {

    @Test
    fun `send and list messages over http`() {
        val setup = createMatchWithFirstChat()

        val firstMessageBody = mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"content":"Hola desde controller"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.senderId", equalTo(setup.userAId.toString())))
            .andExpect(jsonPath("$.content", equalTo("Hola desde controller")))
            .andReturn()
            .response
            .contentAsString
        val firstMessageId = objectMapper.readTree(firstMessageBody).get("id").asString()

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content("""{"content":"Respuesta desde controller"}""")
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].senderId", equalTo(setup.userAId.toString())))

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages?after=$firstMessageId")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messages", hasSize<Any>(1)))
            .andExpect(jsonPath("$.messages[0].senderId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.messages[0].content", equalTo("Respuesta desde controller")))
            .andExpect(jsonPath("$.hasMore", equalTo(false)))
            .andExpect(jsonPath("$.serverTime").exists())
    }

    @Test
    fun `non participant cannot list chat messages`() {
        val setup = createMatchWithFirstChat()
        val stranger = userService.createUser("http-stranger-${java.util.UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(stranger.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error", equalTo("Forbidden")))
    }

    @Test
    fun `non participant cannot get chat`() {
        val setup = createMatchWithFirstChat()
        val stranger = userService.createUser("http-chat-stranger-${java.util.UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}")
                .with(authenticatedAs(stranger.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error", equalTo("Forbidden")))
    }

    @Test
    fun `blank chat message returns bad request`() {
        val setup = createMatchWithFirstChat()

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"content":"   "}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error", equalTo("Bad Request")))
    }

    @Test
    fun `chat message rejects markup`() {
        val setup = createMatchWithFirstChat()

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"content":"<img src=x onerror=alert(1)>"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
    }

    @Test
    fun `invalid chat id returns bad request`() {
        mockMvc.perform(
            get("/api/chats/not-a-uuid/messages")
                .with(authenticatedAs(java.util.UUID.randomUUID()))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error", equalTo("Bad Request")))
    }

    @Test
    fun `mutual cancellation request and acceptance close chat over http`() {
        val setup = createMatchWithFirstChat()
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)

        val exitRequestBody =
            mockMvc.perform(
                post("/api/chats/${setup.firstChatId}/exit-requests")
                    .with(authenticatedAs(setup.userAId))
                    .contentType(jsonContentType)
                    .content("""{"reason":"NO_LONGER_INTERESTED","details":"Mutual cancellation test"}""")
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.type", equalTo(ChatExitRequestType.MUTUAL_CANCEL.name)))
                .andExpect(jsonPath("$.status", equalTo(ChatExitRequestStatus.PENDING.name)))
                .andReturn()
                .response
                .contentAsString

        val exitRequestId = objectMapper.readTree(exitRequestBody).get("id").asString()

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/exit-requests/$exitRequestId/acceptance")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.chat.status", equalTo(ChatStatus.CANCELLED.name)))
            .andExpect(jsonPath("$.exitRequest.status", equalTo(ChatExitRequestStatus.ACCEPTED.name)))
            .andExpect(jsonPath("$.penaltyApplied", equalTo(false)))
    }

    @Test
    fun `mutual cancellation details reject markup`() {
        val setup = createMatchWithFirstChat()

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/exit-requests")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"reason":"NO_LONGER_INTERESTED","details":"<b>cancel</b>"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
    }
}
