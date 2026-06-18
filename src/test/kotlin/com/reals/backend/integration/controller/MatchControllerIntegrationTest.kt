package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.MatchState
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class MatchControllerIntegrationTest : ControllerIT() {

    @Test
    fun `get first chat returns partner and participant decisions`() {
        val setup = createMatchWithFirstChat()
        chatService.recordChatDecision(
            matchId = setup.matchId,
            userId = setup.userAId,
            decision = ChatContinueDecision.APPROVED
        )

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/chat")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(setup.firstChatId.toString())))
            .andExpect(jsonPath("$.expiresAt").exists())
            .andExpect(jsonPath("$.partner.userId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.partner.displayName", equalTo("Match B")))
            .andExpect(jsonPath("$.myDecision", equalTo("APPROVED")))
            .andExpect(jsonPath("$.partnerDecision", equalTo("PENDING")))
    }

    @Test
    fun `chat decision endpoint returns match state after both approvals`() {
        val setup = createMatchWithFirstChat()

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/chat-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state", equalTo(MatchState.CHAT_ACTIVE.name)))

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/chat-decision")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state", equalTo(MatchState.VISUAL_PHASE.name)))
    }

    @Test
    fun `duplicate chat decision maps domain conflict to http 409`() {
        val setup = createMatchWithFirstChat()
        chatService.recordChatDecision(
            matchId = setup.matchId,
            userId = setup.userAId,
            decision = ChatContinueDecision.APPROVED
        )

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/chat-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error", equalTo("Conflict")))
            .andExpect(jsonPath("$.message", containsString("already submitted")))
    }

    @Test
    fun `non participant cannot get match`() {
        val setup = createMatchWithFirstChat()
        val stranger = userService.createUser("match-stranger-${java.util.UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/matches/${setup.matchId}")
                .with(authenticatedAs(stranger.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error", equalTo("Forbidden")))
    }

    @Test
    fun `non participant cannot get visual profile`() {
        val setup = createMatchInVisualPhase()
        val stranger = userService.createUser("visual-stranger-${java.util.UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(stranger.id))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code", equalTo("ACCESS_DENIED")))
            .andExpect(jsonPath("$.error", equalTo("Forbidden")))
    }

    @Test
    fun `visual profile returns myPersonalMessageSubmitted false before message`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myPersonalMessageSubmitted", equalTo(false)))
    }

    @Test
    fun `visual profile returns myPersonalMessageSubmitted true after current user message`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"message":"Me caiste bien"}""")
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myPersonalMessageSubmitted", equalTo(true)))
    }

    @Test
    fun `myPersonalMessageSubmitted is scoped to current user`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"message":"Mensaje de A"}""")
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myPersonalMessageSubmitted", equalTo(false)))

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content("""{"message":"Mensaje de B"}""")
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myPersonalMessageSubmitted", equalTo(true)))
    }

    @Test
    fun `record personal message returns no content`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"message":"Me caiste bien"}""")
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `second personal message returns conflict and does not overwrite first message`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"message":"Primer mensaje"}""")
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"message":"Segundo mensaje"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("DOMAIN_CONFLICT")))

        val review = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Expected visual review")
        assertEquals("Primer mensaje", review.personalMessageA)
    }

    @Test
    fun `personal message rejects markup`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"message":"<script>alert(1)</script>"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
    }

    @Test
    fun `invalid enum request returns bad request`() {
        val setup = createMatchWithFirstChat()

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/chat-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"MAYBE"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error", equalTo("Bad Request")))
    }
}
