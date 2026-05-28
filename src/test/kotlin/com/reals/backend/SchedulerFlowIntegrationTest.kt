package com.reals.backend

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.VisualDecision
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.scheduler.ChatTimeoutJob
import com.reals.backend.scheduler.MatchExpirationJob
import com.reals.backend.scheduler.SchedulingNegotiationTimeoutJob
import com.reals.backend.scheduler.ScheduledSecondChatStartJob
import com.reals.backend.scheduler.VisualPhaseExpirationJob
import com.reals.backend.service.ChatService
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.MatchService
import com.reals.backend.service.ProfileService
import com.reals.backend.service.SchedulingService
import com.reals.backend.service.UserService
import com.reals.backend.service.VisualReviewService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class SchedulerFlowIntegrationTest(
    private val userService: UserService,
    private val profileService: ProfileService,
    private val matchService: MatchService,
    private val chatService: ChatService,
    private val connectionService: ConnectionService,
    private val visualReviewService: VisualReviewService,
    private val schedulingService: SchedulingService,
    private val lockRepository: ActiveEngagementLockRepository,
    private val chatRepository: ChatRepository,
    private val connectionRepository: ConnectionRepository,
    private val matchRepository: MatchRepository,
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val visualReviewRepository: VisualReviewRepository
) {

    @Test
    fun `fresh chat without messages is not considered inactive`() {
        val freshSetup = createMatchWithFirstChat()

        assertFalse(
            chatService.findInactiveChats(inactivityMinutes = 30)
                .any { it.id == freshSetup.firstChatId }
        )

        val oldSetup = createMatchWithFirstChat()
        val oldChat = chatRepository.findById(oldSetup.firstChatId).orElseThrow()
        oldChat.startedAt = OffsetDateTime.now().minusMinutes(31)
        chatRepository.save(oldChat)

        assertTrue(
            chatService.findInactiveChats(inactivityMinutes = 30)
                .any { it.id == oldChat.id }
        )
    }

    @Test
    fun `chat timeout job expires first chat and releases match locks`() {
        val setup = createMatchWithFirstChat()

        chatRepository.updateTimeoutAt(
            chatId = setup.firstChatId,
            timeoutAt = OffsetDateTime.now().minusSeconds(1)
        )

        ChatTimeoutJob(chatService).run()

        assertEquals(ChatStatus.EXPIRED, chatService.findByIdOrThrow(setup.firstChatId).status)
        assertEquals(MatchState.EXPIRED, matchService.findByIdOrThrow(setup.matchId).state)
        assertNoMatchLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `visual phase expiration job expires match and releases match locks`() {
        val setup = createMatchInVisualPhase()

        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = setup.matchId,
            expiresAt = OffsetDateTime.now().minusSeconds(1)
        )

        VisualPhaseExpirationJob(
            visualReviewRepository = visualReviewRepository,
            matchService = matchService
        ).run()

        assertEquals(MatchState.EXPIRED, matchService.findByIdOrThrow(setup.matchId).state)
        assertNoMatchLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `match expiration fallback still expires visual review when no chat matches are expired`() {
        val setup = createMatchInVisualPhase()

        visualReviewRepository.updateExpiresAtByMatchId(
            matchId = setup.matchId,
            expiresAt = OffsetDateTime.now().minusSeconds(1)
        )

        MatchExpirationJob(
            matchRepository = matchRepository,
            visualReviewRepository = visualReviewRepository,
            matchService = matchService,
            maxChatDuration = Duration.ofDays(1)
        ).run()

        assertEquals(MatchState.EXPIRED, matchService.findByIdOrThrow(setup.matchId).state)
        assertNoMatchLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `scheduling timeout job fails negotiation and closes connection`() {
        val setup = createConnectionInSchedulingPhase()

        connectionRepository.updateSchedulingExpiresAt(
            connectionId = setup.connectionId,
            expiresAt = OffsetDateTime.now().minusSeconds(1)
        )

        SchedulingNegotiationTimeoutJob(
            connectionRepository = connectionRepository,
            schedulingService = schedulingService
        ).run()

        assertEquals(
            NegotiationStatus.FAILED,
            schedulingService.findNegotiationOrThrow(setup.connectionId).status
        )
        assertEquals(
            ConnectionState.CLOSED,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
        assertNoConnectionLocks(setup.userAId, setup.userBId)
    }

    @Test
    fun `scheduled second chat job makes due second chat available before activation`() {
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

        assertEquals(
            NegotiationStatus.CONFIRMED,
            schedulingService.findNegotiationOrThrow(setup.connectionId).status
        )
        assertEquals(
            ConnectionState.SECOND_CHAT_SCHEDULED,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
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

        assertEquals(
            ConnectionState.SECOND_CHAT_AVAILABLE,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
        assertEquals(
            ChatStatus.AVAILABLE,
            chatRepository.findByConnectionIdAndChatType(
                setup.connectionId,
                ChatType.SECOND_CHAT
            )?.status
        )
        assertNull(
            chatRepository.findByConnectionIdAndChatType(
                setup.connectionId,
                ChatType.SECOND_CHAT
            )?.activatedAt
        )

        val activeChat =
            chatService.findVisibleSecondChatOrThrow(
                connectionId = setup.connectionId,
                userId = setup.userAId
            )

        assertEquals(ChatStatus.ACTIVE, activeChat.status)
        assertNotNull(activeChat.activatedAt)
        assertEquals(
            ConnectionState.SECOND_CHAT,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
    }

    @Test
    fun `sending first message activates available second chat`() {
        val setup = createAvailableSecondChat()
        val availableChat = chatRepository.findByConnectionIdAndChatType(
            setup.connectionId,
            ChatType.SECOND_CHAT
        ) ?: error("Second chat was not made available")

        assertEquals(ChatStatus.AVAILABLE, availableChat.status)
        assertEquals(
            ConnectionState.SECOND_CHAT_AVAILABLE,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )

        chatService.sendMessage(
            chatId = availableChat.id,
            senderId = setup.userAId,
            content = "Activo el segundo chat con el primer mensaje"
        )

        val activeChat = chatService.findByIdOrThrow(availableChat.id)
        assertEquals(ChatStatus.ACTIVE, activeChat.status)
        assertNotNull(activeChat.activatedAt)
        assertEquals(
            ConnectionState.SECOND_CHAT,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
    }

    private fun createMatchInVisualPhase(): MatchFixture {
        val setup = createMatchWithFirstChat()

        chatService.recordChatDecision(setup.matchId, setup.userAId, ChatContinueDecision.APPROVED)
        chatService.recordChatDecision(setup.matchId, setup.userBId, ChatContinueDecision.APPROVED)

        assertEquals(MatchState.VISUAL_PHASE, matchService.findByIdOrThrow(setup.matchId).state)

        return setup
    }

    private fun createConnectionInSchedulingPhase(): ConnectionFixture {
        val setup = createMatchInVisualPhase()

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

    private fun createAvailableSecondChat(): ConnectionFixture {
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

        return setup
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

    private fun createMatchWithFirstChat(): MatchFixture {
        val userA = createActiveProfile(
            email = "scheduler-a-${UUID.randomUUID()}@example.com",
            displayName = "Scheduler A",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )
        val userB = createActiveProfile(
            email = "scheduler-b-${UUID.randomUUID()}@example.com",
            displayName = "Scheduler B",
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
            bio = "Scheduler test profile"
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

    private fun assertNoMatchLocks(
        userAId: UUID,
        userBId: UUID
    ) {
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(userAId, EngagementType.MATCH))
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(userBId, EngagementType.MATCH))
    }

    private fun assertNoConnectionLocks(
        userAId: UUID,
        userBId: UUID
    ) {
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(userAId, EngagementType.CONNECTION))
        assertEquals(0, lockRepository.countByUserIdAndEngagementType(userBId, EngagementType.CONNECTION))
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
}
