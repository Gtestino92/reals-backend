package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Gender
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ProposalStatus
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class UserFlowAlternateOutcomeIntegrationTest : BaseIT() {

    @Test
    fun `chat rejection moves match to rejected and releases match locks`() {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.REJECTED)

        assertEquals(MatchState.CHAT_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
        assertNoMatchLocks(setup.userAId, setup.userBId)
        assertTrue(penaltyRepository.existsByUserIdAndActiveTrue(setup.userBId))
        assertEquals(ChatStatus.CANCELLED, chatService.findByIdOrThrow(setup.firstChatId).status)
    }

    @Test
    fun `visual rejection ends match without creating connection`() {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.REJECTED)

        assertEquals(MatchState.VISUAL_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
        assertNull(connectionRepository.findByMatchId(setup.matchId))
        assertNoMatchLocks(setup.userAId, setup.userBId)
        assertNoConnectionLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `incompatible queued users do not produce a match`() {
        val userA = createActiveProfile(
            email = "incompatible-a-${UUID.randomUUID()}@example.com",
            displayName = "Incompatible A",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.WOMEN
        )
        val userB = createActiveProfile(
            email = "incompatible-b-${UUID.randomUUID()}@example.com",
            displayName = "Incompatible B",
            gender = Gender.MALE,
            lookingForGender = LookingForGender.WOMEN
        )

        matchmakingService.enqueue(userA)
        matchmakingService.enqueue(userB)

        assertTrue(matchmakingService.findNextCandidatePair() == null)
        assertNoMatchLocks(userA, userB)
        assertFalse(matchExistsForUsers(userA, userB))
    }

    @Test
    fun `basic compatible query can skip an incompatible queued user`() {
        val userA = createActiveProfile(
            email = "compatible-skip-a-${UUID.randomUUID()}@example.com",
            displayName = "Compatible Skip A",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.WOMEN
        )
        val incompatibleUser = createActiveProfile(
            email = "compatible-skip-b-${UUID.randomUUID()}@example.com",
            displayName = "Compatible Skip B",
            gender = Gender.MALE,
            lookingForGender = LookingForGender.WOMEN
        )
        val userC = createActiveProfile(
            email = "compatible-skip-c-${UUID.randomUUID()}@example.com",
            displayName = "Compatible Skip C",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.WOMEN
        )

        matchmakingService.enqueue(userA)
        matchmakingService.enqueue(incompatibleUser)
        matchmakingService.enqueue(userC)

        val pair = matchmakingService.findNextCandidatePair()

        assertEquals(Pair(userA, userC), pair)
        assertFalse(matchExistsForUsers(userA, incompatibleUser))
    }

    @Test
    fun `basic compatible query returns bounded deterministic candidate pairs`() {
        val queuedUsers =
            (1..4).map { index ->
                createActiveProfile(
                    email = "bounded-candidate-$index-${UUID.randomUUID()}@example.com",
                    displayName = "Bounded Candidate $index",
                    gender = Gender.FEMALE,
                    lookingForGender = LookingForGender.WOMEN
                )
            }

        queuedUsers.forEach { matchmakingService.enqueue(it) }

        val candidatePairs =
            matchmakingQueueRepository.findBasicCompatiblePairsSkipLocked(limit = 3)

        assertEquals(3, candidatePairs.size)
        assertEquals(queuedUsers[0], UUID.fromString(candidatePairs[0].userAId))
        assertEquals(queuedUsers[1], UUID.fromString(candidatePairs[0].userBId))
    }

    @Test
    fun `matchmaking service uses FIFO tie breaker when multiple deterministic pairs are compatible`() {
        val queuedUsers =
            (1..4).map { index ->
                createActiveProfile(
                    email = "fifo-candidate-$index-${UUID.randomUUID()}@example.com",
                    displayName = "Fifo Candidate $index",
                    gender = Gender.FEMALE,
                    lookingForGender = LookingForGender.WOMEN
                )
            }

        queuedUsers.forEach { matchmakingService.enqueue(it) }

        val pair = matchmakingService.findNextCandidatePair()

        assertEquals(Pair(queuedUsers[0], queuedUsers[1]), pair)
    }

    @Test
    fun `explicit scheduling rejection opens next round`() {
        val setup = createConnectionInSchedulingPhase()
        val slotA = futureHalfHourSlot()
        val slotB = slotA.plusHours(1)

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTimes = listOf(slotA)
        )
        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            proposedDateTimes = listOf(slotB)
        )

        val negotiation = schedulingService.rejectCurrentRound(
            connectionId = setup.connectionId,
            userId = setup.userAId
        )

        assertEquals(NegotiationStatus.PENDING, negotiation.status)
        assertEquals(2, negotiation.roundNumber)
        assertTrue(
            proposalRepository.findByConnectionId(setup.connectionId)
                .all { it.status == ProposalStatus.REJECTED }
        )
    }

    @Test
    fun `explicit scheduling rejection at max rounds fails negotiation and closes connection`() {
        val setup = createConnectionInSchedulingPhase()
        val baseSlot = futureHalfHourSlot()

        repeat(3) { roundIndex ->
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                proposedDateTimes = listOf(baseSlot.plusHours((roundIndex * 2).toLong()))
            )
            schedulingService.addProposals(
                connectionId = setup.connectionId,
                userId = setup.userBId,
                proposedDateTimes = listOf(baseSlot.plusHours((roundIndex * 2 + 1).toLong()))
            )

            val negotiation = schedulingService.rejectCurrentRound(
                connectionId = setup.connectionId,
                userId = setup.userAId
            )

            if (roundIndex < 2) {
                assertEquals(NegotiationStatus.PENDING, negotiation.status)
                assertEquals(roundIndex + 2, negotiation.roundNumber)
            } else {
                assertEquals(NegotiationStatus.FAILED, negotiation.status)
                assertEquals(ConnectionState.CLOSED, connectionService.findByIdOrThrow(setup.connectionId).state)
                assertNoConnectionLocks(setup.userAId, setup.userBId)
                assertNull(chatRepository.findByConnectionIdAndChatType(setup.connectionId, ChatType.SECOND_CHAT))
            }
        }

        assertTrue(
            proposalRepository.findByConnectionId(setup.connectionId)
                .all { it.status == ProposalStatus.REJECTED }
        )
    }

    @Test
    fun `scheduling auto confirmation chooses best overlap by preference and earliest tie`() {
        val setup = createConnectionInSchedulingPhase()
        val early = futureHalfHourSlot()
        val late = early.plusHours(1)

        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTimes = listOf(late, early)
        )
        schedulingService.addProposals(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            proposedDateTimes = listOf(early, late)
        )

        val negotiation = schedulingService.findNegotiationOrThrow(setup.connectionId)
        assertEquals(NegotiationStatus.CONFIRMED, negotiation.status)
        assertEquals(early.toInstant(), negotiation.confirmedDateTime?.toInstant())

        val accepted = proposalRepository.findByConnectionId(setup.connectionId)
            .filter { it.status == ProposalStatus.ACCEPTED }
        assertEquals(2, accepted.size)
        assertTrue(
            accepted.all {
                it.proposedDateTime.toInstant().equals(early.toInstant())
            }
        )
    }
}
