package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.MatchState
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.S3StorageService
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime

class MatchControllerIntegrationTest : ControllerIT() {

    @MockitoBean
    private lateinit var storageService: S3StorageService

    @BeforeEach
    fun stubPhotoReadUrls() {
        Mockito.`when`(storageService.getReadUrl(anyString()))
            .thenAnswer { invocation ->
                "http://localhost:9000/reals-profile-photos-test/${invocation.arguments[0]}"
            }
    }

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
            .andExpect(jsonPath("$.inactivityExpiresAt").exists())
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
            .andExpect(jsonPath("$.code", equalTo("CHAT_DECISION_ALREADY_SUBMITTED")))
    }

    @Test
    fun `chat decision after first chat is no longer actionable returns stable code`() {
        val setup = createMatchWithFirstChat()
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/chat-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("CHAT_DECISION_NOT_AVAILABLE")))
    }

    @Test
    fun `chat decision after first chat inactivity returns abandoned code`() {
        val setup = createMatchWithFirstChat()
        val chat = chatService.findByIdOrThrow(setup.firstChatId)
        chat.startedAt = OffsetDateTime.now().minusMinutes(6)
        chatRepository.save(chat)

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/chat-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("CHAT_ABANDONED")))
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
        val partnerProfile = profileService.findByUserId(setup.userBId)!!
        val expectedFirstPhotoKey = "users/${setup.userBId}/profile-photos/${partnerProfile.id}-1.jpg"

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myPersonalMessageSubmitted", equalTo(false)))
            .andExpect(jsonPath("$.partnerPersonalMessageSubmitted", equalTo(false)))
            .andExpect(jsonPath("$.partnerPersonalMessageRead", equalTo(true)))
            .andExpect(jsonPath("$.decisionRequiresPartnerPersonalMessageRead", equalTo(false)))
            .andExpect(jsonPath("$.visualExpiresAt").exists())
            .andExpect(
                jsonPath(
                    "$.photos[0].url",
                    equalTo("http://localhost:9000/reals-profile-photos-test/$expectedFirstPhotoKey")
                )
            )
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
            .andExpect(jsonPath("$.partnerPersonalMessageSubmitted", equalTo(false)))
            .andExpect(jsonPath("$.partnerPersonalMessageRead", equalTo(true)))
            .andExpect(jsonPath("$.decisionRequiresPartnerPersonalMessageRead", equalTo(false)))
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
            .andExpect(jsonPath("$.partnerPersonalMessageSubmitted", equalTo(true)))
            .andExpect(jsonPath("$.partnerPersonalMessageRead", equalTo(false)))
            .andExpect(jsonPath("$.decisionRequiresPartnerPersonalMessageRead", equalTo(true)))

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
            .andExpect(jsonPath("$.partnerPersonalMessageSubmitted", equalTo(true)))
            .andExpect(jsonPath("$.partnerPersonalMessageRead", equalTo(false)))
            .andExpect(jsonPath("$.decisionRequiresPartnerPersonalMessageRead", equalTo(true)))
    }

    @Test
    fun `visual profile partner personal message metadata does not mark message as read`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content("""{"message":"Mensaje de B"}""")
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.partnerPersonalMessageSubmitted", equalTo(true)))
            .andExpect(jsonPath("$.partnerPersonalMessageRead", equalTo(false)))
            .andExpect(jsonPath("$.decisionRequiresPartnerPersonalMessageRead", equalTo(true)))

        val review = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Expected visual review")
        assertNull(review.personalMessageBReadByAAt)
    }

    @Test
    fun `partner personal message endpoint marks message as read`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content("""{"message":"Mensaje de B"}""")
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/personal-messages/partner")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message", equalTo("Mensaje de B")))

        val review = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Expected visual review")
        assertNotNull(review.personalMessageBReadByAAt)
    }

    @Test
    fun `visual decision approval before reading partner message returns stable conflict code`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content("""{"message":"Mensaje de B"}""")
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/visual-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("VISUAL_REVIEW_PARTNER_MESSAGE_NOT_READ")))
            .andExpect(
                jsonPath(
                    "$.message",
                    equalTo("Read the partner personal message before making a visual decision.")
                )
            )
    }

    @Test
    fun `visual decision after visual expiration returns stable conflict code`() {
        val setup = createMatchInVisualPhase()

        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = setup.matchId,
            expiresAt = OffsetDateTime.now().minusSeconds(1)
        )

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/visual-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("VISUAL_REVIEW_EXPIRED")))
    }

    @Test
    fun `visual decision rejection before reading partner message returns stable conflict code`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content("""{"message":"Mensaje de B"}""")
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/visual-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"REJECTED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("VISUAL_REVIEW_PARTNER_MESSAGE_NOT_READ")))
            .andExpect(
                jsonPath(
                    "$.message",
                    equalTo("Read the partner personal message before making a visual decision.")
                )
            )
    }

    @Test
    fun `visual decision approval succeeds after reading partner message`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content("""{"message":"Mensaje de B"}""")
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/personal-messages/partner")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/visual-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state", equalTo(MatchState.VISUAL_PHASE.name)))
    }

    @Test
    fun `visual decision rejection succeeds after reading partner message`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userBId))
                .contentType(jsonContentType)
                .content("""{"message":"Mensaje de B"}""")
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/personal-messages/partner")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/visual-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"REJECTED"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state", equalTo(MatchState.VISUAL_PHASE.name)))
    }

    @Test
    fun `visual decision approval succeeds when partner message does not exist`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/visual-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state", equalTo(MatchState.VISUAL_PHASE.name)))
    }

    @Test
    fun `visual decision rejection succeeds when partner message does not exist`() {
        val setup = createMatchInVisualPhase()

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/visual-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"REJECTED"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state", equalTo(MatchState.VISUAL_PHASE.name)))
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
