package com.reals.backend.integration.controller

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainException
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class UserBlockBack3IntegrationTest : ControllerIT() {

    @Test
    fun `manual block contains active first chat without exit penalty or reliability side effects`() {
        val setup = createMatchWithFirstChat()

        userBlockCommandService.blockUserAndContain(
            blockerUserId = setup.userAId,
            blockedUserId = setup.userBId,
            source = UserBlockSource.MANUAL
        )

        val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
        assertEquals(ChatStatus.CANCELLED, chat.status)
        assertEquals(ChatEndReason.USER_BLOCK, chat.endedReason)
        assertEquals(MatchState.CHAT_REJECTED, matchRepository.findById(setup.matchId).orElseThrow().state)
        assertNoMatchLocks(setup.userAId, setup.userBId)
        assertEquals(0, chatExitRequestRepository.findByChatIdOrderByCreatedAtDesc(setup.firstChatId).size)
        assertEquals(0, penaltyRepository.count())
        assertEquals(0, userReliabilityEventRepository.count())
    }

    @Test
    fun `manual block contains visual phase without synthesizing visual rejection`() {
        val setup = createMatchInVisualPhase()

        userBlockCommandService.blockUserAndContain(
            blockerUserId = setup.userAId,
            blockedUserId = setup.userBId,
            source = UserBlockSource.MANUAL
        )

        assertEquals(MatchState.VISUAL_REJECTED, matchRepository.findById(setup.matchId).orElseThrow().state)
        assertNoMatchLocks(setup.userAId, setup.userBId)

        val review = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Expected visual review")
        assertNull(review.userAVisualDecision)
        assertNull(review.userBVisualDecision)
    }

    @Test
    fun `manual block contains scheduling connection and active second chat`() {
        val schedulingSetup = createConnectionInSchedulingPhase()

        userBlockCommandService.blockUserAndContain(
            blockerUserId = schedulingSetup.userAId,
            blockedUserId = schedulingSetup.userBId,
            source = UserBlockSource.MANUAL
        )

        assertEquals(ConnectionState.CLOSED, connectionRepository.findById(schedulingSetup.connectionId).orElseThrow().state)
        assertNoConnectionLocks(schedulingSetup.userAId, schedulingSetup.userBId)

        val secondChatSetup = createActiveSecondChat()

        userBlockCommandService.blockUserAndContain(
            blockerUserId = secondChatSetup.userAId,
            blockedUserId = secondChatSetup.userBId,
            source = UserBlockSource.MANUAL
        )

        val secondChat = chatRepository.findById(secondChatSetup.secondChatId).orElseThrow()
        assertEquals(ChatStatus.CANCELLED, secondChat.status)
        assertEquals(ChatEndReason.USER_BLOCK, secondChat.endedReason)
        assertEquals(ConnectionState.CLOSED, connectionRepository.findById(secondChatSetup.connectionId).orElseThrow().state)
        assertNoConnectionLocks(secondChatSetup.userAId, secondChatSetup.userBId)
    }

    @Test
    fun `manual block does not rewrite terminal historical second chat end reason`() {
        val setup = createActiveSecondChat()
        val chat = chatRepository.findById(setup.secondChatId).orElseThrow()
        chat.status = ChatStatus.CLOSED
        chat.endedReason = ChatEndReason.SYSTEM_CLOSED
        chatRepository.saveAndFlush(chat)

        userBlockCommandService.blockUserAndContain(
            blockerUserId = setup.userAId,
            blockedUserId = setup.userBId,
            source = UserBlockSource.MANUAL
        )

        val containedChat = chatRepository.findById(setup.secondChatId).orElseThrow()
        assertEquals(ChatStatus.CLOSED, containedChat.status)
        assertEquals(ChatEndReason.SYSTEM_CLOSED, containedChat.endedReason)
        assertEquals(ConnectionState.CLOSED, connectionRepository.findById(setup.connectionId).orElseThrow().state)
    }

    @Test
    fun `blocked pair cannot send chat message and reads remain available`() {
        val setup = createMatchWithFirstChat()
        userBlockService.blockUser(setup.userAId, setup.userBId, UserBlockSource.MANUAL)

        assertBlocked {
            chatService.sendMessage(
                chatId = setup.firstChatId,
                senderId = setup.userAId,
                content = "This should not persist"
            )
        }

        assertEquals(0, chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(setup.firstChatId).size)
        chatService.findByIdForUserOrThrow(setup.firstChatId, setup.userAId)
    }

    @Test
    fun `blocked pair cannot approve first chat but rejected path remains available`() {
        val approvedSetup = createMatchWithFirstChat("blocked-approval")
        userBlockService.blockUser(approvedSetup.userAId, approvedSetup.userBId, UserBlockSource.MANUAL)

        assertBlocked {
            chatService.recordChatDecision(
                matchId = approvedSetup.matchId,
                userId = approvedSetup.userAId,
                decision = ChatContinueDecision.APPROVED
            )
        }

        val blockedDecision = chatDecisionRepository.findByMatchId(approvedSetup.matchId)
        assertNull(blockedDecision?.userADecision)
        assertNull(blockedDecision?.userBDecision)

        val rejectedSetup = createMatchWithFirstChat("blocked-rejection")
        userBlockService.blockUser(rejectedSetup.userAId, rejectedSetup.userBId, UserBlockSource.MANUAL)

        chatService.recordChatDecision(
            matchId = rejectedSetup.matchId,
            userId = rejectedSetup.userAId,
            decision = ChatContinueDecision.REJECTED
        )

        assertEquals(ChatStatus.CANCELLED, chatRepository.findById(rejectedSetup.firstChatId).orElseThrow().status)
        assertEquals(MatchState.CHAT_REJECTED, matchRepository.findById(rejectedSetup.matchId).orElseThrow().state)
    }

    @Test
    fun `blocked pair cannot write visual personal message`() {
        val setup = createMatchInVisualPhase()
        userBlockService.blockUser(setup.userAId, setup.userBId, UserBlockSource.MANUAL)

        assertBlocked {
            visualReviewService.recordPersonalMessage(
                matchId = setup.matchId,
                userId = setup.userAId,
                message = "This should not persist"
            )
        }

        val review = visualReviewRepository.findByMatchId(setup.matchId)
            ?: error("Expected visual review")
        assertNull(review.personalMessageA)
        assertNull(review.personalMessageB)
    }

    @Test
    fun `blocked pair cannot submit scheduling proposals or reliability event`() {
        val setup = createConnectionInSchedulingPhase()
        val beforeNegotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        val beforeConnection = connectionRepository.findById(setup.connectionId).orElseThrow()

        userBlockService.blockUser(setup.userAId, setup.userBId, UserBlockSource.MANUAL)

        assertBlocked {
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                expectedRoundNumber = 1,
                proposedDateTimes = listOf(futureHalfHourSlot())
            )
        }

        assertEquals(0, proposalRepository.findByConnectionId(setup.connectionId).size)
        assertEquals(
            false,
            userReliabilityEventRepository.existsByUserIdAndEventTypeAndRelatedConnectionId(
                userId = setup.userAId,
                eventType = UserReliabilityEventType.SCHEDULING_SLOTS_PROPOSED_ON_TIME,
                relatedConnectionId = setup.connectionId
            )
        )

        val afterNegotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        assertEquals(beforeNegotiation.status, afterNegotiation.status)
        assertEquals(NegotiationStatus.PENDING, afterNegotiation.status)
        assertEquals(beforeNegotiation.roundNumber, afterNegotiation.roundNumber)
        assertEquals(beforeNegotiation.confirmedDateTime, afterNegotiation.confirmedDateTime)

        val afterConnection = connectionRepository.findById(setup.connectionId).orElseThrow()
        assertEquals(beforeConnection.state, afterConnection.state)
        assertEquals(ConnectionState.SCHEDULING_PHASE, afterConnection.state)
        assertEquals(beforeConnection.schedulingExpiresAt, afterConnection.schedulingExpiresAt)
    }

    @Test
    fun `safety cancellation preserves safety report chat end reason after block containment`() {
        val setup = createMatchWithFirstChat()

        chatExitService.cancelChatForSafety(
            chatId = setup.firstChatId,
            reporterUserId = setup.userAId,
            reason = ChatExitReason.INAPPROPRIATE_BEHAVIOR,
            details = "Unsafe chat content"
        )

        val chat = chatRepository.findById(setup.firstChatId).orElseThrow()
        assertEquals(ChatStatus.CANCELLED, chat.status)
        assertEquals(ChatEndReason.SAFETY_REPORT, chat.endedReason)

        val block = userBlockRepository.findByBlockerUserIdAndBlockedUserId(setup.userAId, setup.userBId)
        assertEquals(UserBlockSource.SAFETY_REPORT, block?.source)
    }

    @Test
    fun `home filters blocked stale active match and connection without deleting rows`() {
        val matchSetup = createMatchWithFirstChat("home-blocked-match")
        userBlockService.blockUser(matchSetup.userAId, matchSetup.userBId, UserBlockSource.MANUAL)

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(matchSetup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.pendingSchedulingConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.actionableConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))

        assertTrue(matchRepository.findById(matchSetup.matchId).isPresent)

        val connectionSetup = createConnectionInSchedulingPhase()
        userBlockService.blockUser(connectionSetup.userAId, connectionSetup.userBId, UserBlockSource.MANUAL)

        mockMvc.perform(
            get("/api/me/home")
                .with(authenticatedAs(connectionSetup.userAId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeInteractionsSummary.activeInitialCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.activeConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.pendingSchedulingConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.activeInteractionsSummary.actionableConnectionCount", equalTo(0)))
            .andExpect(jsonPath("$.pendingActions.length()", equalTo(0)))
            .andExpect(jsonPath("$.nextSteps.length()", equalTo(0)))

        assertTrue(connectionRepository.findById(connectionSetup.connectionId).isPresent)
        assertEquals(ConnectionState.SCHEDULING_PHASE, connectionRepository.findById(connectionSetup.connectionId).orElseThrow().state)
    }

    private fun assertBlocked(action: () -> Unit) {
        val exception = assertThrows<DomainException> {
            action()
        }
        assertEquals(DomainErrorCode.USER_PAIR_BLOCKED, exception.code)
    }
}
