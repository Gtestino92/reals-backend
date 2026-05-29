package com.reals.backend.integration.service

import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.MatchState
import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatExitIntegrationTest : BaseIT() {

    @Test
    fun `mutual first chat cancellation closes without penalties`() {
        val setup = createMatchWithFirstChat()

        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = setup.firstChatId,
                requesterUserId = setup.userAId
            )

        val outcome =
            chatExitService.acceptMutualCancellation(
                chatId = setup.firstChatId,
                requestId = exitRequest.id,
                responderUserId = setup.userBId
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(MatchState.CHAT_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
        assertFalse(outcome.penaltyApplied)
        assertNoActivePenalties(setup.userAId, setup.userBId)
    }

    @Test
    fun `safety cancellation penalizes reported participant`() {
        val setup = createMatchWithFirstChat()

        val outcome =
            chatExitService.cancelChatForSafety(
                chatId = setup.firstChatId,
                reporterUserId = setup.userAId,
                reason = ChatExitReason.INAPPROPRIATE_BEHAVIOR
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(setup.userBId, outcome.penalizedUserId)
        assertFalse(penaltyRepository.existsByUserIdAndActiveTrue(setup.userAId))
        assertTrue(penaltyRepository.existsByUserIdAndActiveTrue(setup.userBId))
    }

    @Test
    fun `unilateral second chat cancellation before minimum messages applies penalty`() {
        val setup = createActiveSecondChat()

        val outcome =
            chatExitService.cancelChatUnilaterally(
                chatId = setup.secondChatId,
                userId = setup.userAId
            )

        assertEquals(ChatStatus.CANCELLED, outcome.chat.status)
        assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(setup.connectionId).state)
        assertEquals(setup.userAId, outcome.penalizedUserId)
        assertTrue(penaltyRepository.existsByUserIdAndActiveTrue(setup.userAId))
    }
}
