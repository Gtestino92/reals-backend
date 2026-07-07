package com.reals.backend.integration.service

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Gender
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ProposalStatus
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
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
        val chat = chatService.findByIdOrThrow(setup.firstChatId)
        assertEquals(ChatStatus.CANCELLED, chat.status)
        assertEquals(ChatEndReason.UNILATERAL_CANCEL, chat.endedReason)
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
            lookingForGenders = setOf(Gender.FEMALE)
        )
        val userB = createActiveProfile(
            email = "incompatible-b-${UUID.randomUUID()}@example.com",
            displayName = "Incompatible B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)

        assertTrue(matchmakingService.findNextCandidatePair() == null)
        assertNoMatchLocks(userA, userB)
        assertFalse(matchExistsForUsers(userA, userB))
    }

    @Test
    fun `blocked queued users are excluded from candidate pairs`() {
        val userA = createActiveProfile(
            email = "blocked-candidate-a-${UUID.randomUUID()}@example.com",
            displayName = "Blocked Candidate A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "blocked-candidate-b-${UUID.randomUUID()}@example.com",
            displayName = "Blocked Candidate B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

        userBlockService.blockUser(
            blockerUserId = userA,
            blockedUserId = userB,
            source = UserBlockSource.MANUAL
        )
        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)

        val candidatePairs =
            matchmakingQueueRepository.findBasicCompatiblePairsSkipLocked(
                limit = 5,
                today = LocalDate.now()
            )

        assertTrue(candidatePairs.isEmpty())
        assertNull(matchmakingService.findNextCandidatePair())
        assertFalse(matchExistsForUsers(userA, userB))
    }

    @Test
    fun `match service rejects blocked pairs defensively`() {
        val userA = createActiveProfile(
            email = "blocked-match-a-${UUID.randomUUID()}@example.com",
            displayName = "Blocked Match A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "blocked-match-b-${UUID.randomUUID()}@example.com",
            displayName = "Blocked Match B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

        userBlockService.blockUser(
            blockerUserId = userB,
            blockedUserId = userA,
            source = UserBlockSource.MANUAL
        )

        val exception = assertThrows<DomainConflictException> {
            matchService.createMatch(userA, userB)
        }

        assertEquals(DomainErrorCode.USER_PAIR_BLOCKED, exception.code)
        assertFalse(matchExistsForUsers(userA, userB))
    }

    @Test
    fun `basic compatible query can skip an incompatible queued user`() {
        val userA = createActiveProfile(
            email = "compatible-skip-a-${UUID.randomUUID()}@example.com",
            displayName = "Compatible Skip A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        val incompatibleUser = createActiveProfile(
            email = "compatible-skip-b-${UUID.randomUUID()}@example.com",
            displayName = "Compatible Skip B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        val userC = createActiveProfile(
            email = "compatible-skip-c-${UUID.randomUUID()}@example.com",
            displayName = "Compatible Skip C",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(incompatibleUser)
        enqueueForMatchmaking(userC)

        val pair = matchmakingService.findNextCandidatePair()

        assertEquals(Pair(userA, userC), pair)
        assertFalse(matchExistsForUsers(userA, incompatibleUser))
    }

    @Test
    fun `matchmaking applies mutual dynamic age filters`() {
        val userA = createActiveProfile(
            email = "age-filter-a-${UUID.randomUUID()}@example.com",
            displayName = "Age Filter A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            preferredMinAge = 30,
            preferredMaxAge = 40
        )
        val tooYoungForA = createActiveProfile(
            email = "age-filter-b-${UUID.randomUUID()}@example.com",
            displayName = "Age Filter B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE),
            birthDate = LocalDate.now().minusYears(25)
        )
        val acceptedByA = createActiveProfile(
            email = "age-filter-c-${UUID.randomUUID()}@example.com",
            displayName = "Age Filter C",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE),
            birthDate = LocalDate.now().minusYears(35)
        )

        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(tooYoungForA)
        enqueueForMatchmaking(acceptedByA)

        val basicCandidatePairs =
            matchmakingQueueRepository.findBasicCompatiblePairsSkipLocked(
                limit = 5,
                today = LocalDate.now()
            )
        assertFalse(
            basicCandidatePairs.any {
                UUID.fromString(it.userAId) == userA &&
                    UUID.fromString(it.userBId) == tooYoungForA
            }
        )

        val pair = matchmakingService.findNextCandidatePair()

        assertEquals(Pair(userA, acceptedByA), pair)
        assertFalse(matchExistsForUsers(userA, tooYoungForA))
    }

    @Test
    fun `matchmaking applies mutual dynamic distance filters from queue location`() {
        val userA = createActiveProfile(
            email = "distance-filter-a-${UUID.randomUUID()}@example.com",
            displayName = "Distance Filter A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            maxDistanceKm = 10
        )
        val tooFarForA = createActiveProfile(
            email = "distance-filter-b-${UUID.randomUUID()}@example.com",
            displayName = "Distance Filter B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE),
            maxDistanceKm = 10
        )
        val nearA = createActiveProfile(
            email = "distance-filter-c-${UUID.randomUUID()}@example.com",
            displayName = "Distance Filter C",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE),
            maxDistanceKm = 10
        )

        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(
            userId = tooFarForA,
            latitude = -31.4201,
            longitude = -64.1888
        )
        enqueueForMatchmaking(
            userId = nearA,
            latitude = -34.6040,
            longitude = -58.3820
        )

        val pair = matchmakingService.findNextCandidatePair()

        assertEquals(Pair(userA, nearA), pair)
        assertFalse(matchExistsForUsers(userA, tooFarForA))
    }

    @Test
    fun `enqueue updates search location when user is already queued`() {
        val user = createActiveProfile(
            email = "queue-location-refresh-${UUID.randomUUID()}@example.com",
            displayName = "Queue Location Refresh",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

        enqueueForMatchmaking(user)
        val originalEntry = matchmakingQueueRepository.findByUserId(user)
            ?: error("Expected user to be queued")

        enqueueForMatchmaking(
            userId = user,
            latitude = -31.4201,
            longitude = -64.1888,
            accuracyMeters = 25
        )

        val updatedEntry = matchmakingQueueRepository.findByUserId(user)
            ?: error("Expected user to remain queued")

        assertEquals(originalEntry.id, updatedEntry.id)
        assertEquals(originalEntry.enteredAt, updatedEntry.enteredAt)
        assertEquals(-31.4201, updatedEntry.latitude)
        assertEquals(-64.1888, updatedEntry.longitude)
        assertEquals(25, updatedEntry.accuracyMeters)
    }

    @Test
    fun `basic compatible query returns bounded deterministic candidate pairs`() {
        val queuedUsers =
            (1..4).map { index ->
                createActiveProfile(
                    email = "bounded-candidate-$index-${UUID.randomUUID()}@example.com",
                    displayName = "Bounded Candidate $index",
                    gender = Gender.FEMALE,
                    lookingForGenders = setOf(Gender.FEMALE)
                )
            }

        queuedUsers.forEach { enqueueForMatchmaking(it) }

        val candidatePairs =
            matchmakingQueueRepository.findBasicCompatiblePairsSkipLocked(
                limit = 3,
                today = LocalDate.now()
            )

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
                    lookingForGenders = setOf(Gender.FEMALE)
                )
            }

        queuedUsers.forEach { enqueueForMatchmaking(it) }

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
