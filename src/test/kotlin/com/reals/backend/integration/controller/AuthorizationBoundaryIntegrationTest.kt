package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.S3StorageService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
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
                .andExpect(jsonPath("$.error", equalTo("Forbidden")))
                .andExpect(content().string(not(containsString(proposal.id.toString()))))
                .andExpect(jsonPath("$.roundNumber").doesNotExist())
        }
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
}
