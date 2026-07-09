package com.reals.backend.integration.service

import com.reals.backend.controller.dev.DevUserReliabilityController
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.integration.ControllerIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

class UserReliabilityDisabledIntegrationTest : ControllerIT() {

    @Test
    fun `feature flag disabled records no events`() {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        assertFalse(userReliabilityScoreService.enabled)
        assertEquals(0, userReliabilityEventRepository.count())
    }

    @Test
    fun `feature flag disabled allows visual personal message without reliability event`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordPersonalMessage(setup.matchId, setup.userAId, "Mensaje sin reliability")

        assertEquals(
            "Mensaje sin reliability",
            visualReviewService.findByMatchIdOrThrow(setup.matchId).personalMessageA
        )
        assertFalse(userReliabilityScoreService.enabled)
        assertEquals(
            0,
            userReliabilityEventRepository.findAll().count {
                it.eventType == UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED
            }
        )
    }

    @Test
    fun `feature flag disabled has no matchmaking modifier`() {
        assertFalse(userReliabilityScoreService.enabled)
        assertEquals(0.0, userReliabilityScoreService.matchmakingModifierForScores(500.0, -500.0))
    }

    @Test
    fun `local dev reliability endpoint cannot execute in test profile`() {
        val user = userService.createUser("debug-endpoint-absent-${java.util.UUID.randomUUID()}@example.com")

        mockMvc.perform(get("/api/local-dev/user-reliability/${user.id}"))
            .andExpect { result ->
                assertTrue(
                    result.response.status in setOf(401, 403, 404),
                    "Expected local-dev endpoint to be unusable in test profile"
                )
            }
    }

    @Test
    fun `debug response is neutral when feature flag is disabled`() {
        val user = userService.createUser("debug-disabled-${java.util.UUID.randomUUID()}@example.com")
        val controller = DevUserReliabilityController(
            userService = userService,
            userReliabilityScoreService = userReliabilityScoreService
        )

        val response = controller.getUserReliability(user.id).body ?: error("Expected response body")

        assertEquals(user.id, response.userId)
        assertFalse(response.enabled)
        assertEquals(100, response.baseScore)
        assertEquals(0.0, response.weightedDelta)
        assertEquals(100.0, response.effectiveScore)
        assertEquals(emptyList<Any>(), response.events)
        assertEquals(0, userReliabilityEventRepository.count())
    }
}
