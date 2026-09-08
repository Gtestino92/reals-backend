package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatExitRequestStatus
import com.reals.backend.domain.ChatExitRequestType
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageReactionType
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.ChatReplyTargetType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.ChatAudioSendResult
import com.reals.backend.service.ChatAudioService
import com.reals.backend.service.ChatMessageService
import com.reals.backend.service.ChatService
import com.reals.backend.service.LegalComplianceService
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.util.UUID

class ChatControllerIntegrationTest : ControllerIT() {

    @MockitoBean
    private lateinit var chatAudioService: ChatAudioService

    @MockitoSpyBean
    private lateinit var legalComplianceService: LegalComplianceService

    @Test
    fun `child safety cancellation is accepted over http without penalty`() {
        val setup = createMatchWithFirstChat()

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/safety-cancellations")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "reason": "CHILD_SAFETY_CONCERN",
                      "details": "Reported child-safety concern"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.chat.status", equalTo(ChatStatus.CANCELLED.name)))
            .andExpect(jsonPath("$.exitRequest.type", equalTo(ChatExitRequestType.SAFETY_REPORT.name)))
            .andExpect(jsonPath("$.exitRequest.status", equalTo(ChatExitRequestStatus.ACCEPTED.name)))
            .andExpect(jsonPath("$.exitRequest.reason", equalTo(ChatExitReason.CHILD_SAFETY_CONCERN.name)))
            .andExpect(jsonPath("$.penaltyApplied", equalTo(false)))

        val report = safetyReportRepository.findAll().single()
        assertEquals(SafetyReportReason.CHILD_SAFETY_CONCERN, report.reason)
        assertEquals(SafetyReportStatus.PENDING, report.status)
        assertFalse(penaltyRepository.findAll().any { it.userId == setup.userBId })
        assertFalse(userBlockService.isBlockedPair(setup.userAId, setup.userBId))
    }

    @Test
    fun `safety cancellation creates requested block over http`() {
        val setup = createMatchWithFirstChat("http-safety-block")

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/safety-cancellations")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "reason": "INAPPROPRIATE_BEHAVIOR",
                      "details": "Unsafe chat content",
                      "blockUser": true
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.chat.status", equalTo(ChatStatus.CANCELLED.name)))
            .andExpect(jsonPath("$.penaltyApplied", equalTo(false)))

        val report = safetyReportRepository.findAll().single()
        assertEquals(
            com.reals.backend.domain.ChatEndReason.SAFETY_REPORT,
            chatRepository.findById(setup.firstChatId).orElseThrow().endedReason
        )
        val block = userBlockRepository.findByBlockerUserIdAndBlockedUserId(setup.userAId, setup.userBId)
        assertNotNull(block)
        assertEquals(UserBlockSource.SAFETY_REPORT, block?.source)
        assertEquals(report.id, block?.sourceReportId)
    }

    @Test
    fun `get chat includes first chat inactivity deadline and updates after message`() {
        val setup = createMatchWithFirstChat()
        val initialChat = chatService.findByIdOrThrow(setup.firstChatId)

        val initialBody = mockMvc.perform(
            get("/api/chats/${setup.firstChatId}")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inactivityExpiresAt").exists())
            .andReturn()
            .response
            .contentAsString

        assertEquals(
            initialChat.startedAt.plusMinutes(5).toInstant(),
            OffsetDateTime.parse(objectMapper.readTree(initialBody)["inactivityExpiresAt"].asString()).toInstant()
        )

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"content":"Actualiza inactividad"}""")
        )
            .andExpect(status().isOk)

        val reloadedChat = chatService.findByIdOrThrow(setup.firstChatId)
        val updatedBody = mockMvc.perform(
            get("/api/chats/${setup.firstChatId}")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        assertEquals(
            reloadedChat.lastMessageAt!!.plusMinutes(5).toInstant(),
            OffsetDateTime.parse(objectMapper.readTree(updatedBody)["inactivityExpiresAt"].asString()).toInstant()
        )
    }

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
    fun `put message reaction returns canonical message response`() {
        val setup = createMatchWithFirstChat("reaction-http")
        val message = sendMessageOrThrow(setup.firstChatId, setup.userBId, "Mensaje reactable")

        mockMvc.perform(
            put("/api/chats/${setup.firstChatId}/messages/${message.id}/reaction")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"type":"HEART"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(message.id.toString())))
            .andExpect(jsonPath("$.reactionType", equalTo(ChatMessageReactionType.HEART.name)))

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].reactionType", equalTo(ChatMessageReactionType.HEART.name)))
    }

    @Test
    fun `text message send does not require legal compliance lookup at endpoint`() {
        val setup = createMatchWithFirstChat("message-no-legal-lookup")
        Mockito.clearInvocations(legalComplianceService)

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"content":"Message without endpoint legal lookup"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.senderId", equalTo(setup.userAId.toString())))

        Mockito.verify(legalComplianceService, Mockito.never())
            .requireCurrentRequirementsSatisfied(anyUuid())
    }

    @Test
    fun `audio message send does not require legal compliance lookup at endpoint`() {
        val setup = createMatchWithFirstChat("audio-no-legal-lookup")
        val clientMessageId = UUID.randomUUID()
        val audioMessage = ChatMessage(
            id = UUID.randomUUID(),
            chatSessionId = setup.firstChatId,
            senderId = setup.userAId,
            messageType = ChatMessageType.AUDIO,
            clientMessageId = clientMessageId,
            content = null,
            audioBucket = "reals-media-test",
            audioObjectKey = "chats/${setup.firstChatId}/messages/$clientMessageId.m4a",
            audioContentType = "audio/mp4",
            audioSizeBytes = 3,
            audioDurationMillis = 1_000,
            audioSha256 = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"
        )
        Mockito.`when`(
            chatAudioService.sendAudioMessage(
                chatId = eqUuid(setup.firstChatId),
                senderId = eqUuid(setup.userAId),
                clientMessageId = eqUuid(clientMessageId),
                contentType = Mockito.eq("audio/mp4"),
                bytes = anyBytes(),
                replyTarget = Mockito.isNull(ChatMessageService.ChatReplyTarget::class.java)
            )
        ).thenReturn(ChatAudioSendResult.Created(audioMessage))
        Mockito.clearInvocations(legalComplianceService)

        mockMvc.perform(
            multipart("/api/chats/${setup.firstChatId}/audio-messages")
                .file(
                    MockMultipartFile(
                        "file",
                        "message.m4a",
                        "audio/mp4",
                        byteArrayOf(1, 2, 3)
                    )
                )
                .file(
                    MockMultipartFile(
                        "clientMessageId",
                        "",
                        "text/plain",
                        clientMessageId.toString().toByteArray(Charsets.UTF_8)
                    )
                )
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id", equalTo(audioMessage.id.toString())))
            .andExpect(jsonPath("$.messageType", equalTo(ChatMessageType.AUDIO.name)))

        Mockito.verify(legalComplianceService, Mockito.never())
            .requireCurrentRequirementsSatisfied(anyUuid())
        Mockito.verify(chatAudioService).sendAudioMessage(
            chatId = eqUuid(setup.firstChatId),
            senderId = eqUuid(setup.userAId),
            clientMessageId = eqUuid(clientMessageId),
            contentType = Mockito.eq("audio/mp4"),
            bytes = anyBytes(),
            replyTarget = Mockito.isNull(ChatMessageService.ChatReplyTarget::class.java)
        )
    }

    @Test
    fun `first chat guidance next request returns user scoped state over http`() {
        val setup = createMatchWithFirstChat("guidance-http")

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(mapOf("content" to "a".repeat(60))))
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/guidance/next-request")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.question.id").exists())
            .andExpect(jsonPath("$.question.text").exists())
            .andExpect(jsonPath("$.questionOrdinal", equalTo(1)))
            .andExpect(jsonPath("$.maxQuestions", equalTo(3)))
            .andExpect(jsonPath("$.requiredCharacters", equalTo(60)))
            .andExpect(jsonPath("$.requiredParticipationScore", equalTo(60)))
            .andExpect(jsonPath("$.directQuestionReplyMultiplier", equalTo(2)))
            .andExpect(jsonPath("$.progressionAction", equalTo("NEXT_QUESTION")))
            .andExpect(jsonPath("$.canRequestNext", equalTo(false)))
            .andExpect(jsonPath("$.myNextRequested", equalTo(true)))
            .andExpect(jsonPath("$.completed", equalTo(false)))
            .andExpect(jsonPath("$.partnerNextRequested").doesNotExist())
            .andExpect(jsonPath("$.partnerCanRequestNext").doesNotExist())

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/chat")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.guidance.myNextRequested", equalTo(false)))
            .andExpect(jsonPath("$.guidance.partnerNextRequested").doesNotExist())
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
    fun `initial message list returns latest default page as chronological legacy array`() {
        val setup = createMatchWithFirstChat("messages-default-page")
        insertMessages(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            count = 201
        )

        val body = mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(200)))
            .andExpect(jsonPath("$[0].content", equalTo("message-001")))
            .andExpect(jsonPath("$[199].content", equalTo("message-200")))
            .andExpect(jsonPath("$.messages").doesNotExist())
            .andReturn()
            .response
            .contentAsString

        assertEquals(true, objectMapper.readTree(body).isArray)
    }

    @Test
    fun `initial message list applies explicit limit and preserves chronological order`() {
        val setup = createMatchWithFirstChat("messages-explicit-page")
        insertMessages(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            count = 5
        )

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages?limit=3")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(3)))
            .andExpect(jsonPath("$[0].content", equalTo("message-002")))
            .andExpect(jsonPath("$[1].content", equalTo("message-003")))
            .andExpect(jsonPath("$[2].content", equalTo("message-004")))
    }

    @Test
    fun `incremental message list pages after cursor and reports hasMore`() {
        val setup = createMatchWithFirstChat("messages-incremental-page")
        val messages = insertMessages(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            count = 6
        )

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages?after=${messages[2].id}&limit=2")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messages", hasSize<Any>(2)))
            .andExpect(jsonPath("$.messages[0].content", equalTo("message-003")))
            .andExpect(jsonPath("$.messages[1].content", equalTo("message-004")))
            .andExpect(jsonPath("$.hasMore", equalTo(true)))
            .andExpect(jsonPath("$.serverTime").exists())

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages?after=${messages[4].id}&limit=2")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messages", hasSize<Any>(1)))
            .andExpect(jsonPath("$.messages[0].content", equalTo("message-005")))
            .andExpect(jsonPath("$.hasMore", equalTo(false)))
    }

    @Test
    fun `incremental message pagination uses id tie breaker for identical sentAt`() {
        val setup = createMatchWithFirstChat("messages-tie-break")
        val sentAt = OffsetDateTime.now().minusMinutes(1)
        val messages = (1..5).map { index ->
            ChatMessage(
                id = UUID.fromString("00000000-0000-0000-0000-00000000000$index"),
                chatSessionId = setup.firstChatId,
                senderId = setup.userAId,
                content = "tie-$index",
                sentAt = sentAt
            )
        }
        chatMessageRepository.saveAll(messages.reversed())
        chatMessageRepository.flush()

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages?after=${messages[1].id}&limit=2")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messages", hasSize<Any>(2)))
            .andExpect(jsonPath("$.messages[0].id", equalTo(messages[2].id.toString())))
            .andExpect(jsonPath("$.messages[0].content", equalTo("tie-3")))
            .andExpect(jsonPath("$.messages[1].id", equalTo(messages[3].id.toString())))
            .andExpect(jsonPath("$.messages[1].content", equalTo("tie-4")))
            .andExpect(jsonPath("$.hasMore", equalTo(true)))

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages?after=${messages[3].id}&limit=2")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messages", hasSize<Any>(1)))
            .andExpect(jsonPath("$.messages[0].id", equalTo(messages[4].id.toString())))
            .andExpect(jsonPath("$.messages[0].content", equalTo("tie-5")))
            .andExpect(jsonPath("$.hasMore", equalTo(false)))
    }

    @Test
    fun `message list rejects invalid limits with validation response`() {
        val setup = createMatchWithFirstChat("messages-invalid-limit")

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages?limit=0")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))

        mockMvc.perform(
            get("/api/chats/${setup.firstChatId}/messages?limit=501")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
    }

    @Test
    fun `expired read only second chat remains readable through bounded message list`() {
        val setup = createActiveSecondChat()
        chatService.sendMessage(setup.secondChatId, setup.userAId, "Mensaje retenido")
        chatRepository.updateTimeoutAt(
            chatId = setup.secondChatId,
            timeoutAt = OffsetDateTime.now().minusSeconds(1)
        )
        chatService.expireSecondChatToReadOnly(setup.secondChatId)

        mockMvc.perform(
            get("/api/chats/${setup.secondChatId}/messages?limit=1")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].content", equalTo("Mensaje retenido")))
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
    fun `send message after first chat inactivity returns stable abandoned code`() {
        val setup = createMatchWithFirstChat()
        val chat = chatService.findByIdOrThrow(setup.firstChatId)
        chat.startedAt = OffsetDateTime.now().minusMinutes(6)
        chatRepository.save(chat)

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"content":"Tarde por inactividad"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("CHAT_ABANDONED")))

        assertEquals(ChatStatus.ABANDONED, chatService.findByIdOrThrow(setup.firstChatId).status)
        assertEquals(MatchState.EXPIRED, matchRepository.findById(setup.matchId).orElseThrow().state)
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

    @Test
    fun `chat message accepts multiline plain text`() {
        val setup = createMatchWithFirstChat()
        val body = mapOf(
            "content" to "Primera línea\nSegunda línea"
        )

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isOk)
            .andExpect(
                jsonPath(
                    "$.content",
                    equalTo("Primera línea\nSegunda línea")
                )
            )
    }

    @Test
    fun `chat message normalizes CRLF to LF`() {
        val setup = createMatchWithFirstChat()
        val body = mapOf(
            "content" to "Primera línea\r\nSegunda línea"
        )

        mockMvc.perform(
            post("/api/chats/${setup.firstChatId}/messages")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isOk)
            .andExpect(
                jsonPath(
                    "$.content",
                    equalTo("Primera línea\nSegunda línea")
                )
            )
    }

    @Test
    fun `audio reply multipart accepts text plain reply target parts`() {
        val setup = createMatchWithFirstChat("audio-reply-http")
        val targetMessage =
            sendMessageOrThrow(
                setup.firstChatId,
                setup.userBId,
                "Mensaje citado",
            )

        val clientMessageId = UUID.randomUUID()

        val audioMessage = ChatMessage(
            id = UUID.randomUUID(),
            chatSessionId = setup.firstChatId,
            senderId = setup.userAId,
            messageType = ChatMessageType.AUDIO,
            clientMessageId = clientMessageId,
            content = null,
            audioBucket = "reals-media-test",
            audioObjectKey = "chats/${setup.firstChatId}/messages/$clientMessageId.m4a",
            audioContentType = "audio/mp4",
            audioSizeBytes = 3,
            audioDurationMillis = 1_000,
            audioSha256 = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
            replyToMessageId = targetMessage.id,
        )

        Mockito.`when`(
            chatAudioService.sendAudioMessage(
                chatId = eqUuid(setup.firstChatId),
                senderId = eqUuid(setup.userAId),
                clientMessageId = eqUuid(clientMessageId),
                contentType = Mockito.eq("audio/mp4"),
                bytes = anyBytes(),
                replyTarget = Mockito.eq(
                    ChatMessageService.ChatReplyTarget(
                        type = ChatReplyTargetType.MESSAGE,
                        targetId = targetMessage.id,
                    )
                ),
            )
        ).thenReturn(ChatAudioSendResult.Created(audioMessage))

        mockMvc.perform(
            multipart("/api/chats/${setup.firstChatId}/audio-messages")
                .file(
                    MockMultipartFile(
                        "file",
                        "message.m4a",
                        "audio/mp4",
                        byteArrayOf(1, 2, 3),
                    )
                )
                .file(
                    MockMultipartFile(
                        "clientMessageId",
                        "",
                        "text/plain",
                        clientMessageId.toString().toByteArray(),
                    )
                )
                .file(
                    MockMultipartFile(
                        "replyToType",
                        "",
                        "text/plain",
                        "MESSAGE".toByteArray(),
                    )
                )
                .file(
                    MockMultipartFile(
                        "replyToTargetId",
                        "",
                        "text/plain",
                        targetMessage.id.toString().toByteArray(),
                    )
                )
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isCreated)

        Mockito.verify(chatAudioService).sendAudioMessage(
            chatId = eqUuid(setup.firstChatId),
            senderId = eqUuid(setup.userAId),
            clientMessageId = eqUuid(clientMessageId),
            contentType = Mockito.eq("audio/mp4"),
            bytes = anyBytes(),
            replyTarget = Mockito.eq(
                ChatMessageService.ChatReplyTarget(
                    type = ChatReplyTargetType.MESSAGE,
                    targetId = targetMessage.id,
                )
            ),
        )
    }

    private fun pendingMutualRequests(chatId: UUID) =
        chatExitRequestRepository.findByChatIdOrderByCreatedAtDesc(chatId)
            .filter {
                it.type == ChatExitRequestType.MUTUAL_CANCEL &&
                    it.status == ChatExitRequestStatus.PENDING
            }

    private fun insertMessages(
        chatId: UUID,
        senderId: UUID,
        count: Int
    ): List<ChatMessage> {
        val baseSentAt = OffsetDateTime.now().minusMinutes(count.toLong() + 1)
        val messages = (0 until count).map { index ->
            ChatMessage(
                chatSessionId = chatId,
                senderId = senderId,
                content = "message-${index.toString().padStart(3, '0')}",
                sentAt = baseSentAt.plusSeconds(index.toLong())
            )
        }
        chatMessageRepository.saveAll(messages)
        chatMessageRepository.flush()
        return messages
    }

    private fun eqUuid(value: UUID): UUID {
        Mockito.eq(value)
        return value
    }

    private fun anyUuid(): UUID {
        Mockito.any(UUID::class.java)
        return UUID.randomUUID()
    }

    private fun anyBytes(): ByteArray {
        Mockito.any(ByteArray::class.java)
        return byteArrayOf()
    }
}
