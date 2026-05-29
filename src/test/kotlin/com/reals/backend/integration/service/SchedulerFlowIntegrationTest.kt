package com.reals.backend.integration.service

import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.scheduler.ChatTimeoutJob
import com.reals.backend.scheduler.MatchExpirationJob
import com.reals.backend.scheduler.ScheduledSecondChatStartJob
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

class SchedulerFlowIntegrationTest : BaseIT() {

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
}
