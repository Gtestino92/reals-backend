package com.reals.backend.integration.service

import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.LookingForGender
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.scheduler.ChatTimeoutJob
import com.reals.backend.scheduler.MatchmakingJob
import com.reals.backend.scheduler.MatchExpirationJob
import com.reals.backend.scheduler.ScheduledSecondChatStartJob
import com.reals.backend.scheduler.SchedulingActivationJob
import com.reals.backend.scheduler.SchedulingNegotiationTimeoutJob
import com.reals.backend.scheduler.VisualPhaseExpirationJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class SchedulerFlowIntegrationTest : BaseIT() {

    @Test
    fun `matchmaking job creates match and first chat from queued users`() {
        val userA = createActiveProfile(
            email = "matchmaking-job-a-${UUID.randomUUID()}@example.com",
            displayName = "Job A",
            gender = Gender.FEMALE,
            lookingForGender = LookingForGender.MEN
        )
        val userB = createActiveProfile(
            email = "matchmaking-job-b-${UUID.randomUUID()}@example.com",
            displayName = "Job B",
            gender = Gender.MALE,
            lookingForGender = LookingForGender.WOMEN
        )

        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)

        MatchmakingJob(
            matchmakingProcessorService = matchmakingProcessorService,
            maxPairsPerRun = 5
        ).run()

        val match =
            matchRepository.findAll()
                .single {
                    (it.userAId == userA && it.userBId == userB) ||
                        (it.userAId == userB && it.userBId == userA)
                }

        assertEquals(MatchState.CHAT_ACTIVE, match.state)
        assertEquals(
            ChatType.FIRST_CHAT,
            chatRepository.findByMatchIdAndChatType(
                match.id,
                ChatType.FIRST_CHAT
            )?.chatType
        )
        assertFalse(matchmakingQueueRepository.existsByUserId(userA))
        assertFalse(matchmakingQueueRepository.existsByUserId(userB))
    }

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
    fun `scheduling activation job enables due pending connection`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")

        assertEquals(ConnectionState.SCHEDULING_PENDING, connection.state)
        assertNoConnectionLocks(setup.userAId, setup.userBId)
        assertNull(schedulingService.findNegotiationOrNull(connection.id))

        connectionRepository.updateSchedulingAvailableAt(
            connectionId = connection.id,
            availableAt = OffsetDateTime.now().minusSeconds(1)
        )

        SchedulingActivationJob(
            connectionRepository = connectionRepository,
            connectionService = connectionService,
            schedulingService = schedulingService
        ).run()

        assertEquals(
            ConnectionState.SCHEDULING_PHASE,
            connectionRepository.findById(connection.id).orElseThrow().state
        )
        assertNotNull(schedulingService.findNegotiationOrNull(connection.id))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.CONNECTION))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.CONNECTION))
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

    @Test
    fun `expired second chat is not visible by connection`() {
        val setup = createActiveSecondChat()

        chatRepository.updateTimeoutAt(
            chatId = setup.secondChatId,
            timeoutAt = OffsetDateTime.now().minusSeconds(1)
        )

        ChatTimeoutJob(chatService).runNowForDev()

        assertEquals(ChatStatus.EXPIRED, chatService.findByIdOrThrow(setup.secondChatId).status)
        assertEquals(
            ConnectionState.CLOSED,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )

        kotlin.test.assertFailsWith<IllegalStateException> {
            chatService.findVisibleSecondChatOrThrow(
                connectionId = setup.connectionId,
                userId = setup.userAId
            )
        }
    }
}
