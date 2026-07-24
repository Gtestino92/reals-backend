package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ProposalStatus
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class HappyPathIntegrationTest : BaseIT() {

    @Test
    fun `happy path creates and closes a full connection flow`() {
        val userA = createActiveProfile(
            email = "ana-${UUID.randomUUID()}@example.com",
            displayName = "Ana",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "bruno-${UUID.randomUUID()}@example.com",
            displayName = "Bruno",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)

        val pair = matchmakingService.claimNextCandidatePair()
            ?: error("Expected a candidate pair")
        val match = matchService.createMatch(pair.first, pair.second)
        val firstChat = chatService.startFirstChat(match.id)

        assertEquals(MatchState.CHAT_ACTIVE, match.state)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(userA, EngagementType.MATCH))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(userB, EngagementType.MATCH))

        chatService.sendMessage(firstChat.id, userA, "Hola desde A")
        chatService.sendMessage(firstChat.id, userB, "Hola desde B")

        chatService.recordChatDecision(match.id, userA, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(match.id, userB, ChatContinueDecision.APPROVED)

        val decision = chatDecisionRepository.findByMatchId(match.id)
        assertNotNull(decision)
        assertEquals(ChatContinueDecision.APPROVED, decision?.userADecision)
        assertEquals(ChatContinueDecision.APPROVED, decision?.userBDecision)
        assertEquals(MatchState.VISUAL_PHASE, matchService.findByIdOrThrow(match.id).state)
        assertEquals(ChatStatus.FINISHED, chatRepository.findById(firstChat.id).orElseThrow().status)

        visualReviewService.recordPersonalMessage(match.id, userA, "Sigamos conversando")
        visualReviewService.recordPersonalMessage(match.id, userB, "Dale")
        assertEquals("Dale", visualReviewService.getPartnerMessage(match.id, userA))
        assertEquals("Sigamos conversando", visualReviewService.getPartnerMessage(match.id, userB))
        visualReviewService.recordDecision(match.id, userA, VisualDecision.APPROVED)
        visualReviewService.recordDecision(match.id, userB, VisualDecision.APPROVED)

        val approvedMatch = matchService.findByIdOrThrow(match.id)
        assertEquals(MatchState.VISUAL_APPROVED, approvedMatch.state)

        val connection = connectionRepository.findByMatchId(match.id)
            ?: error("Connection was not created")
        assertEquals(ConnectionState.SCHEDULING_PENDING, connection.state)
        assertNoMatchLocks(userA, userB)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(userA, EngagementType.CONNECTION))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(userB, EngagementType.CONNECTION))

        connectionRepository.updateSchedulingAvailableAt(
            connectionId = connection.id,
            availableAt = OffsetDateTime.now().minusSeconds(1)
        )
        connectionService.activateScheduling(connection.id)
        schedulingService.initializeNegotiation(connection.id)

        assertEquals(ConnectionState.SCHEDULING_PHASE, connectionService.findByIdOrThrow(connection.id).state)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(userA, EngagementType.CONNECTION))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(userB, EngagementType.CONNECTION))

        val slot = futureHalfHourSlot()
        val proposalA = schedulingService.addProposals(
            connectionId = connection.id,
            userId = userA,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slot.plusHours(1), slot)
        )
        assertEquals(2, proposalA.size)
        assertTrue(proposalA.all { it.status == ProposalStatus.PENDING })

        schedulingService.addProposals(
            connectionId = connection.id,
            userId = userB,
            expectedRoundNumber = 1,
            proposedDateTimes = listOf(slot, slot.plusHours(2))
        )

        val negotiation = schedulingService.findNegotiationOrThrow(connection.id)
        assertEquals(NegotiationStatus.CONFIRMED, negotiation.status)
        assertEquals(slot.toInstant(), negotiation.confirmedDateTime?.toInstant())
        assertEquals(ConnectionState.SECOND_CHAT_SCHEDULED, connectionService.findByIdOrThrow(connection.id).state)

        val proposals = proposalRepository.findByConnectionId(connection.id)
        assertEquals(4, proposals.size)
        assertEquals(2, proposals.count { it.status == ProposalStatus.ACCEPTED })
        assertEquals(2, proposals.count { it.status == ProposalStatus.REJECTED })

        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = connection.id,
            confirmedDateTime = OffsetDateTime.now().minusSeconds(1)
        )

        val joined = secondChatLifecycleService.joinSecondChat(connection.id, userA)
        val secondChat = chatService.findByIdOrThrow(joined.chatId!!)
        secondChatLifecycleService.joinSecondChat(connection.id, userB)
        assertEquals(ChatStatus.ACTIVE, secondChat.status)
        assertEquals(ConnectionState.SECOND_CHAT, connectionService.findByIdOrThrow(connection.id).state)

        chatService.sendMessage(secondChat.id, userA, "Ya quedo habilitado el segundo chat")
        chatService.sendMessage(secondChat.id, userB, "Seguimos por aca")

        val exitRequest =
            chatExitService.requestMutualCancellation(
                chatId = secondChat.id,
                requesterUserId = userA
            )
        chatExitService.acceptMutualCancellation(
            chatId = secondChat.id,
            requestId = exitRequest.id,
            responderUserId = userB
        )

        assertEquals(ChatStatus.CANCELLED, chatService.findByIdOrThrow(secondChat.id).status)
        assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(connection.id).state)
        assertNoConnectionLocks(userA, userB)
    }
}
