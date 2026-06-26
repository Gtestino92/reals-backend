package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatExitRequestStatus
import com.reals.backend.domain.ChatExitRequestType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.util.UUID

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
    fun `list messages afterMessageId alias returns only newer messages`() {
        val setup = createMatchWithFirstChat()

        val firstMessageBody = mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"content":"Primer mensaje"}""")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val firstMessageId = objectMapper.readTree(firstMessageBody).get("id").asString()

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content("""{"content":"Segundo mensaje"}""")
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages?afterMessageId=$firstMessageId")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messages", hasSize<Any>(1)))
            .andExpect(jsonPath("$.messages[0].content", equalTo("Segundo mensaje")))
            .andExpect(jsonPath("$.hasMore", equalTo(false)))
    }

    @Test
    fun `list messages rejects after anchor from another chat`() {
        val setup = createMatchWithFirstChat("anchor-target")
        val otherSetup = createMatchWithFirstChat("anchor-other")

        val otherMessageBody = mockMvc.perform(
            post("/api/chats/${otherSetup.firstChatId}/messages")
                .with(authenticatedAs(otherSetup.userAId))
                .contentType(jsonContentType)
                .content("""{"content":"Mensaje de otro chat"}""")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val otherMessageId = objectMapper.readTree(otherMessageBody).get("id").asString()

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages?after=$otherMessageId")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("CHAT_NOT_AVAILABLE")))
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
            .andExpect(jsonPath("$.code", equalTo("CHAT_MESSAGE_INVALID")))
            .andExpect(jsonPath("$.error", equalTo("Bad Request")))
    }

    @Test
    fun `too long chat message returns stable bad request code`() {
        val setup = createMatchWithFirstChat()
        val body = mapOf("content" to "x".repeat(1001))

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("CHAT_MESSAGE_INVALID")))
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
            .andExpect(jsonPath("$.code", equalTo("CHAT_MESSAGE_INVALID")))
    }

    @Test
    fun `missing chat returns stable not found code`() {
        val missingChatId = UUID.randomUUID()

        mockMvc.perform(
            post("/api/chats/$missingChatId/messages")
                .with(authenticatedAs(UUID.randomUUID()))
                .contentType(jsonContentType)
                .content("""{"content":"Hola"}""")
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code", equalTo("CHAT_NOT_FOUND")))
    }

    @Test
    fun `send message after chat timeout returns stable expired code`() {
        val setup = createMatchWithFirstChat()
        chatRepository.updateTimeoutAt(
            chatId = setup.firstChatId,
            timeoutAt = OffsetDateTime.now().minusSeconds(1)
        )

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"content":"Tarde"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("CHAT_EXPIRED")))
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
    fun `mutual cancellation request first post creates and duplicate same requester returns existing over http`() {
        val setup = createMatchWithFirstChat()

        val firstResponse =
            mockMvc.perform(
                post("/api/chats/${setup.firstChatId}/exit-requests")
                    .with(authenticatedAs(setup.userAId))
                    .contentType(jsonContentType)
                    .content("""{"reason":"OTHER","details":"Original reason"}""")
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.type", equalTo(ChatExitRequestType.MUTUAL_CANCEL.name)))
                .andExpect(jsonPath("$.status", equalTo(ChatExitRequestStatus.PENDING.name)))
                .andExpect(jsonPath("$.reason", equalTo(ChatExitReason.OTHER.name)))
                .andExpect(jsonPath("$.details", equalTo("Original reason")))
                .andReturn()
                .response
                .contentAsString

        val firstRequestId = objectMapper.readTree(firstResponse).get("id").asString()
        assertEquals(1, pendingMutualRequests(setup.firstChatId).size)

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/exit-requests")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"reason":"HARASSMENT","details":"Replacement attempt"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(firstRequestId)))
            .andExpect(jsonPath("$.type", equalTo(ChatExitRequestType.MUTUAL_CANCEL.name)))
            .andExpect(jsonPath("$.status", equalTo(ChatExitRequestStatus.PENDING.name)))
            .andExpect(jsonPath("$.reason", equalTo(ChatExitReason.OTHER.name)))
            .andExpect(jsonPath("$.details", equalTo("Original reason")))

        val pending = pendingMutualRequests(setup.firstChatId)
        assertEquals(1, pending.size)
        assertEquals(firstRequestId, pending.single().id.toString())
        assertEquals(ChatExitReason.OTHER, pending.single().reason)
        assertEquals("Original reason", pending.single().details)
    }

    @Test
    fun `mutual cancellation request from partner while pending returns conflict over http`() {
        val setup = createMatchWithFirstChat()

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/exit-requests")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"reason":"NO_LONGER_INTERESTED","details":"Initial request"}""")
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/exit-requests")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content("""{"reason":"OTHER","details":"Partner request"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("CHAT_EXIT_REQUEST_ALREADY_PENDING")))

        assertEquals(1, pendingMutualRequests(setup.firstChatId).size)
    }

    @Test
    fun `mutual cancellation rejection returns outcome over http`() {
        val setup = createMatchWithFirstChat()
        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = setup.firstChatId,
                requesterUserId = setup.userAId
            )

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/exit-requests/${exitRequest.id}/rejection")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.chat.status", equalTo(ChatStatus.CANCELLED.name)))
            .andExpect(jsonPath("$.exitRequest.status", equalTo(ChatExitRequestStatus.REJECTED.name)))
            .andExpect(jsonPath("$.penaltyApplied", equalTo(false)))
    }

    @Test
    fun `mutual cancellation timeout returns outcome over http`() {
        val setup = createMatchWithFirstChat()
        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = setup.firstChatId,
                requesterUserId = setup.userAId
            )
        exitRequest.createdAt = OffsetDateTime.now().minusSeconds(30)
        chatExitRequestRepository.save(exitRequest)

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/exit-requests/${exitRequest.id}/timeout")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.chat.status", equalTo(ChatStatus.CANCELLED.name)))
            .andExpect(jsonPath("$.exitRequest.status", equalTo(ChatExitRequestStatus.TIMED_OUT.name)))
            .andExpect(jsonPath("$.penaltyApplied", equalTo(false)))
    }

    @Test
    fun `mutual cancellation timeout before timeout returns conflict over http`() {
        val setup = createMatchWithFirstChat()
        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = setup.firstChatId,
                requesterUserId = setup.userAId
            )

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/exit-requests/${exitRequest.id}/timeout")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("CHAT_EXIT_REQUEST_NOT_AVAILABLE")))
    }

    @Test
    fun `missing exit request returns stable not found code`() {
        val setup = createMatchWithFirstChat()

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/exit-requests/${UUID.randomUUID()}/acceptance")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code", equalTo("CHAT_EXIT_REQUEST_NOT_FOUND")))
    }

    @Test
    fun `mutual cancellation timeout as non participant returns forbidden over http`() {
        val setup = createMatchWithFirstChat()
        val stranger = userService.createUser("http-timeout-stranger-${UUID.randomUUID()}@example.com")
        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = setup.firstChatId,
                requesterUserId = setup.userAId
            )
        exitRequest.createdAt = OffsetDateTime.now().minusSeconds(30)
        chatExitRequestRepository.save(exitRequest)

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/exit-requests/${exitRequest.id}/timeout")
                .with(authenticatedAs(stranger.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code", equalTo("ACCESS_DENIED")))
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
            .andExpect(jsonPath("$.code", equalTo("CHAT_MESSAGE_INVALID")))
    }

    private fun pendingMutualRequests(chatId: UUID) =
        chatExitRequestRepository.findByChatIdOrderByCreatedAtDesc(chatId)
            .filter {
                it.type == ChatExitRequestType.MUTUAL_CANCEL &&
                    it.status == ChatExitRequestStatus.PENDING
            }
}
