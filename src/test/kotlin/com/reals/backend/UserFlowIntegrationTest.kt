package com.reals.backend

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.domain.ProposalStatus
import com.reals.backend.domain.VisualDecision
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.ChatDecisionRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.ScheduleProposalRepository
import com.reals.backend.scheduler.ScheduledSecondChatStartJob
import com.reals.backend.service.ChatExitService
import com.reals.backend.service.ChatService
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.MatchService
import com.reals.backend.service.MatchmakingService
import com.reals.backend.service.ProfileService
import com.reals.backend.service.SchedulingService
import com.reals.backend.service.UserService
import com.reals.backend.service.VisualReviewService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class UserFlowIntegrationTest(
    private val userService: UserService,
    private val profileService: ProfileService,
    private val matchmakingService: MatchmakingService,
    private val matchService: MatchService,
    private val chatService: ChatService,
    private val chatExitService: ChatExitService,
    private val visualReviewService: VisualReviewService,
    private val connectionService: ConnectionService,
    private val schedulingService: SchedulingService,
    private val lockRepository: ActiveEngagementLockRepository,
    private val chatDecisionRepository: ChatDecisionRepository,
    private val chatRepository: ChatRepository,
    private val connectionRepository: ConnectionRepository,
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val proposalRepository: ScheduleProposalRepository,
    private val penaltyRepository: PenaltyRepository
) {

    @Test
    fun `happy path creates and closes a full connection flow`() {
        val userA = createActiveProfile(
            email = "ana-${UUID.randomUUID()}@example.com",
            displayName = "Ana",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )
        val userB = createActiveProfile(
            email = "bruno-${UUID.randomUUID()}@example.com",
            displayName = "Bruno",
            gender = Gender.MALE,
            lookingForGender = LookingForGender.WOMEN
        )

        matchmakingService.enqueue(userA)
        matchmakingService.enqueue(userB)

        val pair = matchmakingService.findCandidatePairs(batchSize = 1).single()
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
        assertEquals(ConnectionState.SCHEDULING_PHASE, connection.state)
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(userA, EngagementType.MATCH))
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(userB, EngagementType.MATCH))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(userA, EngagementType.CONNECTION))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(userB, EngagementType.CONNECTION))

        val slot = futureHalfHourSlot()
        val proposalA = schedulingService.addProposals(
            connectionId = connection.id,
            userId = userA,
            proposedDateTimes = listOf(slot.plusHours(1), slot)
        )
        assertEquals(2, proposalA.size)
        assertTrue(proposalA.all { it.status == ProposalStatus.PENDING })

        schedulingService.addProposals(
            connectionId = connection.id,
            userId = userB,
            proposedDateTimes = listOf(slot, slot.plusHours(2))
        )

        val negotiation = schedulingService.findNegotiationOrThrow(connection.id)
        assertEquals(NegotiationStatus.CONFIRMED, negotiation.status)
        assertEquals(slot.toInstant(), negotiation.confirmedDateTime?.toInstant())
        assertEquals(
            ConnectionState.SECOND_CHAT_SCHEDULED,
            connectionService.findByIdOrThrow(connection.id).state
        )

        val proposals = proposalRepository.findByConnectionId(connection.id)
        assertEquals(4, proposals.size)
        assertEquals(2, proposals.count { it.status == ProposalStatus.ACCEPTED })
        assertEquals(2, proposals.count { it.status == ProposalStatus.REJECTED })

        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = connection.id,
            confirmedDateTime = OffsetDateTime.now().minusSeconds(1)
        )

        ScheduledSecondChatStartJob(
            negotiationRepository = negotiationRepository,
            connectionService = connectionService,
            chatService = chatService
        ).run()

        assertEquals(ConnectionState.SECOND_CHAT_AVAILABLE, connectionService.findByIdOrThrow(connection.id).state)

        val availableSecondChat = chatRepository.findByConnectionIdAndChatType(
            connection.id,
            com.reals.backend.domain.ChatType.SECOND_CHAT
        ) ?: error("Second chat was not made available")
        assertEquals(ChatStatus.AVAILABLE, availableSecondChat.status)

        val secondChat = chatService.findVisibleSecondChatOrThrow(connection.id, userA)
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
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(userA, EngagementType.CONNECTION))
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(userB, EngagementType.CONNECTION))
    }

    @Test
    fun `profile cannot be activated without required photos`() {
        val user = userService.createUser("draft-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = "Draft",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.OTHER,
            lookingForGender = LookingForGender.EVERYONE,
            intention = Intention.DATE,
            city = "Buenos Aires",
            country = "AR",
            bio = null
        )

        assertThrows<IllegalStateException> {
            profileService.activateProfile(profile.id)
        }

        assertEquals(ProfileStatus.DRAFT, profileService.findByIdOrThrow(profile.id).status)
    }

    @Test
    fun `draft profile cannot enter matchmaking`() {
        val user = userService.createUser("queue-draft-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Draft Queue",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN,
            intention = Intention.DATE,
            city = "Buenos Aires",
            country = "AR",
            bio = null
        )

        assertThrows<IllegalStateException> {
            matchmakingService.enqueue(user.id)
        }
    }

    @Test
    fun `chat decision cannot be submitted twice by the same user`() {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(
            matchId = setup.matchId,
            userId = setup.userAId,
            decision = ChatContinueDecision.APPROVED
        )

        assertThrows<IllegalStateException> {
            chatService.recordChatDecision(
                matchId = setup.matchId,
                userId = setup.userAId,
                decision = ChatContinueDecision.APPROVED
            )
        }
    }

    @Test
    fun `visual approval requires reading partner personal message when present`() {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        visualReviewService.recordPersonalMessage(setup.matchId, setup.userBId, "Me caiste bien")

        assertThrows<IllegalStateException> {
            visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        }

        assertEquals("Me caiste bien", visualReviewService.getPartnerMessage(setup.matchId, setup.userAId))

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
    }

    @Test
    fun `chat rejection moves match to rejected and releases match locks`() {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.REJECTED)

        assertEquals(MatchState.CHAT_REJECTED, matchService.findByIdOrThrow(setup.matchId).state)
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.MATCH))
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.MATCH))
        assertTrue(penaltyRepository.existsByUserIdAndActiveTrue(setup.userBId))
        assertEquals(ChatStatus.CANCELLED, chatService.findByIdOrThrow(setup.firstChatId).status)
    }

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
        assertEquals(false, outcome.penaltyApplied)
        assertEquals(false, penaltyRepository.existsByUserIdAndActiveTrue(setup.userAId))
        assertEquals(false, penaltyRepository.existsByUserIdAndActiveTrue(setup.userBId))
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
        assertEquals(false, penaltyRepository.existsByUserIdAndActiveTrue(setup.userAId))
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

    @Test
    fun `non participant cannot send a chat message`() {
        val setup = createMatchWithFirstChat()
        val stranger = userService.createUser("stranger-${UUID.randomUUID()}@example.com")

        assertThrows<IllegalStateException> {
            chatService.sendMessage(setup.firstChatId, stranger.id, "No pertenezco a este match")
        }
    }

    @Test
    fun `non participant cannot add scheduling proposal`() {
        val setup = createConnectionInSchedulingPhase()
        val stranger = userService.createUser("proposal-stranger-${UUID.randomUUID()}@example.com")

        assertThrows<IllegalStateException> {
            schedulingService.addProposal(
                connectionId = setup.connectionId,
                userId = stranger.id,
                proposedDateTime = futureHalfHourSlot()
            )
        }
    }

    @Test
    fun `user cannot accept own proposal`() {
        val setup = createConnectionInSchedulingPhase()
        val proposal = schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = futureHalfHourSlot()
        )

        assertThrows<IllegalStateException> {
            schedulingService.acceptProposal(
                proposalId = proposal.id,
                acceptorUserId = setup.userAId
            )
        }
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

        val proposals = proposalRepository.findByConnectionId(setup.connectionId)
        val accepted = proposals.filter { it.status == ProposalStatus.ACCEPTED }
        assertEquals(2, accepted.size)
        assertTrue(
            accepted.all {
                it.proposedDateTime.toInstant().equals(early.toInstant())
            }
        )
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

    private fun createActiveProfile(
        email: String,
        displayName: String,
        gender: Gender,
        lookingForGender: LookingForGender
    ): UUID {
        val user = userService.createUser(email)
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = displayName,
            birthDate = LocalDate.of(1995, 1, 1),
            gender = gender,
            lookingForGender = lookingForGender,
            intention = Intention.DATE,
            city = "Buenos Aires",
            country = "AR",
            bio = "Test profile"
        )

        repeat(4) { index ->
            profileService.addPhoto(
                profileId = profile.id,
                url = "https://example.com/${profile.id}-${index + 1}.jpg",
                position = index + 1,
                isPersonPhoto = index == 0,
                isFullBody = index == 0
            )
        }

        profileService.activateProfile(profile.id)
        return user.id
    }

    private fun createMatchWithFirstChat(): MatchFixture {
        val userA = createActiveProfile(
            email = "match-a-${UUID.randomUUID()}@example.com",
            displayName = "Match A",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )
        val userB = createActiveProfile(
            email = "match-b-${UUID.randomUUID()}@example.com",
            displayName = "Match B",
            gender = Gender.MALE,
            lookingForGender = LookingForGender.WOMEN
        )

        val match = matchService.createMatch(userA, userB)
        val chat = chatService.startFirstChat(match.id)

        return MatchFixture(
            userAId = userA,
            userBId = userB,
            matchId = match.id,
            firstChatId = chat.id
        )
    }

    private fun createConnectionInSchedulingPhase(): ConnectionFixture {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")

        return ConnectionFixture(
            userAId = setup.userAId,
            userBId = setup.userBId,
            matchId = setup.matchId,
            connectionId = connection.id
        )
    }

    private fun createActiveSecondChat(): ActiveSecondChatFixture {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = slot
        )
        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            proposedDateTime = slot
        )
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = OffsetDateTime.now().minusSeconds(1)
        )

        ScheduledSecondChatStartJob(
            negotiationRepository = negotiationRepository,
            connectionService = connectionService,
            chatService = chatService
        ).run()

        val secondChat =
            chatRepository.findByConnectionIdAndChatType(
                setup.connectionId,
                ChatType.SECOND_CHAT
            ) ?: error("Second chat was not made available")

        chatService.findVisibleSecondChatOrThrow(
            connectionId = setup.connectionId,
            userId = setup.userAId
        )

        return ActiveSecondChatFixture(
            userAId = setup.userAId,
            userBId = setup.userBId,
            matchId = setup.matchId,
            connectionId = setup.connectionId,
            secondChatId = secondChat.id
        )
    }

    private fun futureHalfHourSlot(): OffsetDateTime {
        val candidate = OffsetDateTime.now()
            .plusDays(1)
            .withSecond(0)
            .withNano(0)

        return if (candidate.minute < 30) {
            candidate.withMinute(30)
        } else {
            candidate.plusHours(1).withMinute(0)
        }
    }

    private data class MatchFixture(
        val userAId: UUID,
        val userBId: UUID,
        val matchId: UUID,
        val firstChatId: UUID
    )

    private data class ConnectionFixture(
        val userAId: UUID,
        val userBId: UUID,
        val matchId: UUID,
        val connectionId: UUID
    )

    private data class ActiveSecondChatFixture(
        val userAId: UUID,
        val userBId: UUID,
        val matchId: UUID,
        val connectionId: UUID,
        val secondChatId: UUID
    )
}
