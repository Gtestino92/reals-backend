package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Gender
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.S3StorageService
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        Mockito.`when`(storageService.getReadUrl(anyString(), anyString()))
            .thenAnswer { invocation ->
                "http://localhost:9000/${invocation.arguments[0]}/${invocation.arguments[1]}"
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

        val beforeRequest = OffsetDateTime.now()
        val result = mockMvc.perform(
            get("/api/matches/${setup.matchId}/chat")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(setup.firstChatId.toString())))
            .andExpect(jsonPath("$.expiresAt").exists())
            .andExpect(jsonPath("$.inactivityExpiresAt").doesNotExist())
            .andExpect(jsonPath("$.partner.userId", equalTo(setup.userBId.toString())))
            .andExpect(jsonPath("$.partner.displayName", equalTo("Match B")))
            .andExpect(jsonPath("$.myDecision", equalTo("APPROVED")))
            .andExpect(jsonPath("$.partnerDecision", equalTo("PENDING")))
            .andExpect(jsonPath("$.serverTime").exists())
            .andExpect(jsonPath("$.serverTime").isNotEmpty())
            .andExpect(jsonPath("$.guidance.question.id").exists())
            .andExpect(jsonPath("$.guidance.question.text").exists())
            .andExpect(jsonPath("$.guidance.question.answerCode").doesNotExist())
            .andExpect(jsonPath("$.guidance.question.conversationKind").doesNotExist())
            .andExpect(jsonPath("$.guidance.question.conversationPotential").doesNotExist())
            .andExpect(jsonPath("$.guidance.question.categoryId").doesNotExist())
            .andExpect(jsonPath("$.guidance.question.semanticVersion").doesNotExist())
            .andExpect(jsonPath("$.guidance.questionOrdinal", equalTo(1)))
            .andExpect(jsonPath("$.guidance.maxQuestions", equalTo(3)))
            .andExpect(jsonPath("$.guidance.question.instanceId").exists())
            .andExpect(jsonPath("$.guidance.requiredCharacters", equalTo(60)))
            .andExpect(jsonPath("$.guidance.requiredParticipationScore", equalTo(60)))
            .andExpect(jsonPath("$.guidance.directQuestionReplyMultiplier", equalTo(2)))
            .andExpect(jsonPath("$.guidance.progressionAction", equalTo("NEXT_QUESTION")))
            .andExpect(jsonPath("$.guidance.canRequestNext", equalTo(false)))
            .andExpect(jsonPath("$.guidance.myNextRequested", equalTo(false)))
            .andExpect(jsonPath("$.guidance.completed", equalTo(false)))
            .andExpect(jsonPath("$.guidance.partnerNextRequested").doesNotExist())
            .andExpect(jsonPath("$.guidance.partnerEligible").doesNotExist())
            .andReturn()
        val afterRequest = OffsetDateTime.now()

        val serverTime = OffsetDateTime.parse(
            objectMapper.readTree(result.response.contentAsString).get("serverTime").asString()
        )
        assertFalse(serverTime.toInstant().isBefore(beforeRequest.toInstant()))
        assertFalse(serverTime.toInstant().isAfter(afterRequest.toInstant()))
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
    fun `visual profile is denied after visual expiration even before scheduler runs`() {
        val setup = createMatchInVisualPhase()
        visualReviewRepository.updateExpiresAtByMatchId(setup.matchId, OffsetDateTime.now().minusSeconds(1))

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("VISUAL_REVIEW_EXPIRED")))
    }

    @Test
    fun `visual profile is denied before visual review availability`() {
        val setup = createMatchInDelayedVisualPhase()

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("VISUAL_CONTENT_NOT_AVAILABLE")))
    }

    @Test
    fun `visual profile is denied after visual approval with closed connection`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)
        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")
        connection.state = ConnectionState.CLOSED
        connectionRepository.saveAndFlush(connection)

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("VISUAL_CONTENT_NOT_AVAILABLE")))
    }

    @Test
    fun `partner message read is denied for blocked pair and does not mark read`() {
        val setup = createMatchInVisualPhase()
        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Mensaje B")
        userBlockService.blockUser(setup.userAId, setup.userBId, UserBlockSource.MANUAL)

        mockMvc.perform(
            get("/api/matches/${setup.matchId}/personal-messages/partner")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("USER_PAIR_BLOCKED")))

        assertNull(visualReviewService.findByMatchIdOrThrow(setup.matchId).personalMessageBReadByAAt)
    }

    @Test
    fun `personal message write is denied for rejected visual match`() {
        val setup = createMatchInVisualPhase()
        val match = matchService.findByIdOrThrow(setup.matchId)
        match.state = MatchState.VISUAL_REJECTED
        matchRepository.saveAndFlush(match)

        mockMvc.perform(
            put("/api/matches/${setup.matchId}/personal-messages/me")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"message":"No debe guardarse"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("VISUAL_CONTENT_NOT_AVAILABLE")))

        assertNull(visualReviewService.findByMatchIdOrThrow(setup.matchId).personalMessageA)
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
            .andExpect(jsonPath("$.affinityIndicators.length()", equalTo(0)))
            .andExpect(
                jsonPath(
                    "$.photos[0].url",
                    equalTo("http://localhost:9000/reals-media-test/$expectedFirstPhotoKey")
                )
            )
            .andExpect(jsonPath("$.photos[0].moderationStatus", equalTo("APPROVED")))
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
    fun `visual profile returns symmetric privacy safe affinity indicators`() {
        val setup = createAnsweredMatchWithFirstChat("visual-affinity-indicators")
        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)
        visualReviewService.makeAvailableNowForTest(setup.matchId)

        val userAResponse = mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.affinityIndicators.length()", equalTo(1)))
            .andExpect(jsonPath("$.affinityIndicators[0].categoryId", equalTo("CINEMA_SERIES_AND_STORIES")))
            .andExpect(jsonPath("$.affinityIndicators[0].title").exists())
            .andExpect(jsonPath("$.affinityIndicators[0].answerCode").doesNotExist())
            .andExpect(jsonPath("$.affinityIndicators[0].answerLabel").doesNotExist())
            .andExpect(jsonPath("$.affinityIndicators[0].questionId").doesNotExist())
            .andExpect(jsonPath("$.affinityIndicators[0].semanticVersion").doesNotExist())
            .andExpect(jsonPath("$.affinityIndicators[0].conversationKind").doesNotExist())
            .andExpect(jsonPath("$.affinityIndicators[0].conversationPotential").doesNotExist())
            .andExpect(jsonPath("$.affinityIndicators[0].score").doesNotExist())
            .andExpect(jsonPath("$.affinityIndicators[0].percentage").doesNotExist())
            .andReturn()

        val userBResponse = mockMvc.perform(
            get("/api/matches/${setup.matchId}/visual-profile")
                .with(authenticatedAs(setup.userBId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.affinityIndicators.length()", equalTo(1)))
            .andExpect(jsonPath("$.affinityIndicators[0].categoryId", equalTo("CINEMA_SERIES_AND_STORIES")))
            .andExpect(jsonPath("$.affinityIndicators[0].title").exists())
            .andReturn()

        val userAIndicator = objectMapper.readTree(userAResponse.response.contentAsString).get("affinityIndicators").first()
        val userBIndicator = objectMapper.readTree(userBResponse.response.contentAsString).get("affinityIndicators").first()

        assertEquals(2, userAIndicator.size())
        assertEquals(userAIndicator, userBIndicator)
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
            .andExpect(jsonPath("$.decisionRequiresPartnerPersonalMessageRead", equalTo(false)))

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
            .andExpect(jsonPath("$.decisionRequiresPartnerPersonalMessageRead", equalTo(false)))
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
            .andExpect(jsonPath("$.decisionRequiresPartnerPersonalMessageRead", equalTo(false)))

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
    fun `visual decision approval succeeds before reading partner message`() {
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
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state", equalTo(MatchState.VISUAL_PHASE.name)))

        val review = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Expected visual review")
        assertNull(review.personalMessageBReadByAAt)
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
    fun `visual decision before visual review availability returns stable conflict code`() {
        val setup = createMatchInDelayedVisualPhase()

        mockMvc.perform(
            post("/api/matches/${setup.matchId}/visual-decision")
                .with(authenticatedAs(setup.userAId))
                .contentType(jsonContentType)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("VISUAL_CONTENT_NOT_AVAILABLE")))
    }

    @Test
    fun `visual decision rejection succeeds before reading partner message`() {
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
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state", equalTo(MatchState.VISUAL_PHASE.name)))

        val review = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Expected visual review")
        assertNull(review.personalMessageBReadByAAt)
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

    private fun createAnsweredMatchWithFirstChat(emailPrefix: String): MatchFixture {
        val userA = createActiveProfile(
            email = "$emailPrefix-a-${java.util.UUID.randomUUID()}@example.com",
            displayName = "Match A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "$emailPrefix-b-${java.util.UUID.randomUUID()}@example.com",
            displayName = "Match B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        answerAffinityQuestion(userA, "CINEMA_IMPORTANCE_001", "VERY_IMPORTANT")
        answerAffinityQuestion(userB, "CINEMA_IMPORTANCE_001", "IMPORTANT")
        val match = matchService.createMatch(userA, userB)
        val chat = chatService.startFirstChat(match.id)

        return MatchFixture(
            userAId = userA,
            userBId = userB,
            matchId = match.id,
            firstChatId = chat.id
        )
    }
}
