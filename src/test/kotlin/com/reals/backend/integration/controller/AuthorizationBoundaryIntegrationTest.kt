package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatMessageType.TEXT
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.S3StorageService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import java.time.OffsetDateTime
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ProposalStatus
import org.mockito.Mockito
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class AuthorizationBoundaryIntegrationTest : ControllerIT() {

    @MockitoBean
    private lateinit var storageService: S3StorageService

    @Test
    fun `only connection participants can read the negotiation and submitted proposals`() {
        val pair = createConnectionInSchedulingPhase()
        val unrelated = userService.createUser("scheduling-reader@example.test")
        val proposal = schedulingService.addProposals(
            connectionId = pair.connectionId,
            userId = pair.userAId,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(futureHalfHourSlot())
        ).single()
        val base = "/api/connections/${pair.connectionId}"

        mockMvc.perform(get("$base/negotiation").with(authenticatedAs(pair.userBId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connectionId", equalTo(pair.connectionId.toString())))
        mockMvc.perform(get("$base/proposals").with(authenticatedAs(pair.userBId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id", equalTo(proposal.id.toString())))

        for (resource in listOf("negotiation", "proposals")) {
            mockMvc.perform(get("$base/$resource").with(authenticatedAs(unrelated.id)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code", equalTo("ACCESS_DENIED")))
                .andExpect(jsonPath("$.message", equalTo("Access is denied")))
                .andExpect(jsonPath("$.error", equalTo("Forbidden")))
                .andExpect(content().string(not(containsString(proposal.id.toString()))))
                .andExpect(jsonPath("$.roundNumber").doesNotExist())
        }

        mockMvc.perform(post("/api/matches/${pair.matchId}/block").with(authenticatedAs(pair.userAId)))
            .andExpect(status().isCreated)
        mockMvc.perform(get(base).with(authenticatedAs(pair.userBId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state", equalTo("CLOSED")))
        mockMvc.perform(get("$base/negotiation").with(authenticatedAs(pair.userBId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connectionId", equalTo(pair.connectionId.toString())))
        mockMvc.perform(get("$base/proposals").with(authenticatedAs(pair.userBId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].proposedDateTime").exists())
    }

    @Test
    fun `blocking a visual match stops new partner photo URL resolution`() {
        val pair = createMatchInVisualPhase()
        val base = "/api/matches/${pair.matchId}"
        val marker = "fixture-photo-reference"
        Mockito.`when`(storageService.getReadUrl(Mockito.anyString(), Mockito.anyString())).thenReturn(marker)

        mockMvc.perform(get("$base/visual-profile").with(authenticatedAs(pair.userBId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.photos[0].url", equalTo(marker)))

        mockMvc.perform(post("$base/block").with(authenticatedAs(pair.userAId)))
            .andExpect(status().isCreated)
        Mockito.clearInvocations(storageService)
        mockMvc.perform(get("$base/visual-profile").with(authenticatedAs(pair.userBId)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("USER_PAIR_BLOCKED")))
            .andExpect(jsonPath("$.photos").doesNotExist())
            .andExpect(content().string(not(containsString(marker))))
        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `audio read URL resolution requires membership and a readable chat`() {
        val pair = createActiveSecondChat()
        val unrelated = userService.createUser("audio-reader@example.test")
        val bucket = "fixture-media"
        val key = "fixture/audio.m4a"
        val marker = "fixture-audio-reference"
        val message = chatMessageRepository.saveAndFlush(
            ChatMessage(
                chatSessionId = pair.secondChatId,
                senderId = pair.userAId,
                messageType = ChatMessageType.AUDIO,
                audioBucket = bucket,
                audioObjectKey = key,
                audioContentType = "audio/mp4",
                audioSizeBytes = 32,
                audioDurationMillis = 1000
            )
        )
        Mockito.`when`(storageService.getReadUrl(bucket, key)).thenReturn(marker)
        val path = "/api/chats/${pair.secondChatId}/messages"

        mockMvc.perform(get(path).with(authenticatedAs(pair.userBId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id", equalTo(message.id.toString())))
            .andExpect(jsonPath("$[0].audio.url", equalTo(marker)))
        Mockito.verify(storageService).getReadUrl(bucket, key)
        Mockito.clearInvocations(storageService)

        mockMvc.perform(get(path).with(authenticatedAs(unrelated.id)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code", equalTo("ACCESS_DENIED")))
            .andExpect(jsonPath("$.message", equalTo("Access is denied")))
            .andExpect(content().string(not(containsString(pair.matchId.toString()))))
            .andExpect(content().string(not(containsString(marker))))
            .andExpect(content().string(not(containsString(message.id.toString()))))

        val chat = chatRepository.findById(pair.secondChatId).orElseThrow()
        chat.status = ChatStatus.CLOSED
        chatRepository.saveAndFlush(chat)
        mockMvc.perform(get(path).with(authenticatedAs(pair.userBId)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("CHAT_NOT_AVAILABLE")))
            .andExpect(content().string(not(containsString(marker))))
        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `block suppresses counterpart audio on initial and incremental reads of both chats`() {
        val first = createMatchWithFirstChat("blocked-first-audio")
        assertBlockedAudioHistory(first.matchId, first.firstChatId, first.userAId, first.userBId)

        val second = createActiveSecondChat()
        assertBlockedAudioHistory(second.matchId, second.secondChatId, second.userAId, second.userBId)
    }

    private fun assertBlockedAudioHistory(
        matchId: java.util.UUID,
        chatId: java.util.UUID,
        userAId: java.util.UUID,
        userBId: java.util.UUID
    ) {
        val text = chatMessageRepository.saveAndFlush(ChatMessage(
            chatSessionId = chatId, senderId = userAId, messageType = TEXT, content = "Retained text"
        ))
        val audio = chatMessageRepository.saveAndFlush(ChatMessage(
            chatSessionId = chatId, senderId = userAId, messageType = ChatMessageType.AUDIO,
            audioBucket = "fixture-media", audioObjectKey = "fixture/blocked.m4a",
            audioContentType = "audio/mp4", audioSizeBytes = 32, audioDurationMillis = 1000
        ))
        Mockito.`when`(storageService.getReadUrl("fixture-media", "fixture/blocked.m4a"))
            .thenReturn("fixture-audio-reference")
        val path = "/api/chats/$chatId/messages"

        mockMvc.perform(get(path).with(authenticatedAs(userBId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("fixture-audio-reference")))
        mockMvc.perform(post("/api/matches/$matchId/block").with(authenticatedAs(userAId)))
            .andExpect(status().isCreated)
        Mockito.clearInvocations(storageService)

        assertEquals(ChatStatus.CANCELLED, chatRepository.findById(chatId).orElseThrow().status)
        mockMvc.perform(get(path).param("limit", "1").with(authenticatedAs(userBId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Retained text")))
            .andExpect(content().string(not(containsString(audio.id.toString()))))
        mockMvc.perform(get(path).param("after", text.id.toString()).with(authenticatedAs(userBId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messages").isEmpty)
        Mockito.verifyNoInteractions(storageService)
    }

    @Test
    fun `HEART replays keep text readable but cannot sign counterpart audio after a block`() {
        val pair = createMatchWithFirstChat()
        val path = "/api/chats/${pair.firstChatId}/messages"
        val audio = chatMessageRepository.saveAndFlush(ChatMessage(
            chatSessionId = pair.firstChatId, senderId = pair.userAId, messageType = ChatMessageType.AUDIO,
            audioBucket = "media", audioObjectKey = "voice", audioSizeBytes = 32,
            audioContentType = "audio/mp4", audioDurationMillis = 1000
        ))
        val text = chatMessageRepository.saveAndFlush(ChatMessage(
            chatSessionId = pair.firstChatId, senderId = pair.userAId, content = "retained"
        ))
        fun heart(message: ChatMessage) = mockMvc.perform(
            put("$path/${message.id}/reaction").with(authenticatedAs(pair.userBId))
                .contentType(jsonContentType).content("""{"type":"HEART"}""")
        )
        Mockito.`when`(storageService.getReadUrl("media", "voice")).thenReturn("signed-voice")
        heart(text).andExpect(status().isOk)
        repeat(2) {
            heart(audio).andExpect(status().isOk).andExpect(jsonPath("$.audio.url", equalTo("signed-voice")))
        }

        mockMvc.perform(post("/api/matches/${pair.matchId}/block").with(authenticatedAs(pair.userBId)))
            .andExpect(status().isCreated)
        Mockito.clearInvocations(storageService)
        heart(audio).andExpect(status().isConflict).andExpect(jsonPath("$.code", equalTo("USER_PAIR_BLOCKED")))
            .andExpect(content().string(not(containsString("signed-voice"))))
        heart(text).andExpect(status().isOk).andExpect(jsonPath("$.content", equalTo("retained")))
            .andExpect(jsonPath("$.reactionType", equalTo("HEART")))
        Mockito.verifyNoInteractions(storageService)
        assertEquals("HEART", chatMessageRepository.findById(audio.id).orElseThrow().reactionType?.name)
    }

    @Test
    fun `both participants can page past hidden audio cursors and play only their own audio`() {
        val pair = createActiveSecondChat()
        val path = "/api/chats/${pair.secondChatId}/messages"
        val start = OffsetDateTime.parse("2026-07-01T12:00:00Z")
        fun audio(sender: java.util.UUID, key: String, index: Long) = chatMessageRepository.saveAndFlush(ChatMessage(
            chatSessionId = pair.secondChatId, senderId = sender, messageType = ChatMessageType.AUDIO,
            audioBucket = "media", audioObjectKey = key, audioSizeBytes = 32, audioContentType = "audio/mp4",
            audioDurationMillis = 1000, sentAt = start.plusSeconds(index)
        ))
        val aCursor = audio(pair.userAId, "a", 0)
        val bCursor = audio(pair.userBId, "b", 1)
        val text = chatMessageRepository.saveAndFlush(ChatMessage(
            chatSessionId = pair.secondChatId, senderId = pair.userAId, content = "retained", sentAt = start.plusSeconds(2)
        ))
        val aLast = audio(pair.userAId, "a", 3)
        val bLast = audio(pair.userBId, "b", 4)
        for (key in listOf("a", "b")) {
            Mockito.`when`(storageService.getReadUrl("media", key)).thenReturn("signed-$key")
        }
        for (viewer in listOf(pair.userAId, pair.userBId)) {
            mockMvc.perform(get(path).with(authenticatedAs(viewer))).andExpect(status().isOk)
                .andExpect(content().string(containsString(aCursor.id.toString())))
                .andExpect(content().string(containsString(bCursor.id.toString())))
        }
        mockMvc.perform(post("/api/matches/${pair.matchId}/block").with(authenticatedAs(pair.userAId)))
            .andExpect(status().isCreated)

        for ((own, hidden) in listOf(aLast to bCursor, bLast to aCursor)) {
            val viewer = own.senderId
            Mockito.clearInvocations(storageService)
            mockMvc.perform(get(path).param("limit", "2").with(authenticatedAs(viewer)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].id", equalTo(text.id.toString())))
                .andExpect(jsonPath("$[1].id", equalTo(own.id.toString())))
                .andExpect(jsonPath("$[1].audio.url", equalTo("signed-${own.audioObjectKey}")))
            val next = if (viewer == pair.userAId) text else bCursor
            mockMvc.perform(get(path).param("after", hidden.id.toString()).param("limit", "1").with(authenticatedAs(viewer)))
                .andExpect(status().isOk).andExpect(jsonPath("$.messages[0].id", equalTo(next.id.toString())))
                .andExpect(jsonPath("$.messages[1]").doesNotExist()).andExpect(jsonPath("$.hasMore", equalTo(true)))
            mockMvc.perform(get(path).param("after", text.id.toString()).param("limit", "1").with(authenticatedAs(viewer)))
                .andExpect(status().isOk).andExpect(jsonPath("$.messages[0].id", equalTo(own.id.toString())))
                .andExpect(jsonPath("$.hasMore", equalTo(false)))
            Mockito.verify(storageService, Mockito.never()).getReadUrl("media", hidden.audioObjectKey!!)
        }
    }

    @Test
    fun `foreign proposal acceptance preserves both negotiations and connections`() {
        val first = createConnectionInSchedulingPhase()
        val second = createConnectionInSchedulingPhase()
        val unrelated = userService.createUser("foreign-scheduling@example.test")
        val proposal = schedulingService.addProposals(
            connectionId = first.connectionId, userId = first.userAId,
            expectedRoundNumber = 1, proposedDateTimes = listOf(futureHalfHourSlot())
        ).single()
        val eventCount = userReliabilityEventRepository.count()

        mockMvc.perform(post("/api/connections/${first.connectionId}/proposals/${proposal.id}/acceptance")
            .with(authenticatedAs(unrelated.id)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.message", equalTo("Access is denied")))
        mockMvc.perform(post("/api/connections/${second.connectionId}/proposals/${proposal.id}/acceptance")
            .with(authenticatedAs(second.userBId)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("SCHEDULING_PROPOSAL_NOT_AVAILABLE")))

        for (connectionId in listOf(first.connectionId, second.connectionId)) {
            assertEquals(ConnectionState.SCHEDULING_PHASE, connectionRepository.findById(connectionId).orElseThrow().state)
            val negotiation = schedulingService.findNegotiationOrThrow(connectionId)
            assertEquals(NegotiationStatus.PENDING, negotiation.status)
            assertEquals(1, negotiation.roundNumber)
        }
        assertEquals(ProposalStatus.PENDING, proposalRepository.findById(proposal.id).orElseThrow().status)
        assertEquals(0, proposalRepository.findByConnectionId(second.connectionId).size)
        assertEquals(eventCount, userReliabilityEventRepository.count())

        mockMvc.perform(post("/api/connections/${first.connectionId}/proposals/${proposal.id}/acceptance")
            .with(authenticatedAs(first.userBId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("CONFIRMED")))
    }

    @Test
    fun `block after prior chat termination suppresses old counterpart audio but retains text`() {
        val pair = createMatchWithFirstChat("terminated-before-block")
        chatMessageRepository.saveAndFlush(ChatMessage(
            chatSessionId = pair.firstChatId, senderId = pair.userAId,
            messageType = TEXT, content = "Historical text"
        ))
        val audio = chatMessageRepository.saveAndFlush(ChatMessage(
            chatSessionId = pair.firstChatId, senderId = pair.userAId,
            messageType = ChatMessageType.AUDIO, audioBucket = "fixture-media",
            audioObjectKey = "fixture/old.m4a", audioContentType = "audio/mp4",
            audioSizeBytes = 32, audioDurationMillis = 1000
        ))
        val request = chatExitService.requestMutualCancellation(
            chatId = pair.firstChatId, requesterUserId = pair.userAId
        )
        chatExitService.acceptMutualCancellation(
            chatId = pair.firstChatId, requestId = request.id, responderUserId = pair.userBId
        )
        assertEquals(ChatStatus.CANCELLED, chatRepository.findById(pair.firstChatId).orElseThrow().status)

        mockMvc.perform(post("/api/matches/${pair.matchId}/block").with(authenticatedAs(pair.userAId)))
            .andExpect(status().isCreated)
        Mockito.clearInvocations(storageService)
        mockMvc.perform(get("/api/chats/${pair.firstChatId}/messages").with(authenticatedAs(pair.userBId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Historical text")))
            .andExpect(content().string(not(containsString(audio.id.toString()))))
        Mockito.verifyNoInteractions(storageService)
    }
}
