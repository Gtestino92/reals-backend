package com.reals.backend.integration.service

import com.reals.backend.config.MatchmakingJobProperties
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.PushPlatform
import com.reals.backend.domain.SecondChatAttendanceStatus
import com.reals.backend.domain.SecondChatResolutionRequestStatus
import com.reals.backend.domain.VisualDecision
import com.reals.backend.integration.BaseIT
import com.reals.backend.scheduler.ChatTimeoutJob
import com.reals.backend.scheduler.MatchmakingJob
import com.reals.backend.scheduler.MatchExpirationJob
import com.reals.backend.scheduler.SecondChatLifecycleJob
import com.reals.backend.scheduler.SchedulingActivationJob
import com.reals.backend.scheduler.SchedulingNegotiationTimeoutJob
import com.reals.backend.scheduler.VisualPhaseExpirationJob
import com.reals.backend.service.notification.SchedulingAvailableNotificationService
import com.reals.backend.service.notification.schedulingAvailableAggregateId
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainConflictException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class SchedulerFlowIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var schedulingAvailableNotificationService: SchedulingAvailableNotificationService

    @Test
    fun `matchmaking job creates match and first chat from queued users`() {
        val userA = createActiveProfile(
            email = "matchmaking-job-a-${UUID.randomUUID()}@example.com",
            displayName = "Job A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "matchmaking-job-b-${UUID.randomUUID()}@example.com",
            displayName = "Job B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )

        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)

        MatchmakingJob(
            matchmakingProcessorService = matchmakingProcessorService,
            properties = MatchmakingJobProperties(
                fixedDelay = 60000,
                maxPairsPerRun = 5
            )
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
        assertEquals(ChatEndReason.ABSOLUTE_TIMEOUT, chatService.findByIdOrThrow(setup.firstChatId).endedReason)
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
            visualReviewService = visualReviewService
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `scheduling activation job enables due pending connection`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")

        assertEquals(ConnectionState.SCHEDULING_PENDING, connection.state)
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.CONNECTION))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.CONNECTION))
        assertNull(schedulingService.findNegotiationOrNull(connection.id))

        connectionRepository.updateSchedulingAvailableAt(
            connectionId = connection.id,
            availableAt = OffsetDateTime.now().minusSeconds(1)
        )
        pushDeviceTokenService.registerToken(setup.userAId, "scheduling-job-token-a", PushPlatform.ANDROID)
        pushDeviceTokenService.registerToken(setup.userBId, "scheduling-job-token-b", PushPlatform.ANDROID)

        val beforeActivation = OffsetDateTime.now()
        SchedulingActivationJob(
            connectionRepository = connectionRepository,
            schedulingService = schedulingService,
            schedulingAvailableNotificationService = schedulingAvailableNotificationService
        ).run()
        val afterActivation = OffsetDateTime.now()

        val activatedConnection = connectionRepository.findById(connection.id).orElseThrow()
        assertEquals(ConnectionState.SCHEDULING_PHASE, activatedConnection.state)
        assertFalse(activatedConnection.schedulingExpiresAt.isBefore(beforeActivation.plusMinutes(2880)))
        assertFalse(activatedConnection.schedulingExpiresAt.isAfter(afterActivation.plusMinutes(2880).plusSeconds(1)))
        assertNotNull(schedulingService.findNegotiationOrNull(connection.id))
        val deliveries =
            listOf(setup.userAId, setup.userBId).flatMap { userId ->
                pushNotificationDeliveryRepository.findByNotificationTypeAndAggregateId(
                    notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
                    aggregateId = schedulingAvailableAggregateId(
                        userId = userId,
                        connectionIds = listOf(connection.id)
                    )
                )
            }
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == PushDeliveryStatus.SENT })
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userAId, EngagementType.CONNECTION))
        assertEquals(1, lockRepository.countByUserIdAndEngagementType(setup.userBId, EngagementType.CONNECTION))
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `scheduling activation job leaves pending connection before available timestamp`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")

        connectionRepository.updateSchedulingAvailableAt(
            connectionId = connection.id,
            availableAt = OffsetDateTime.now().plusHours(1)
        )

        val summary =
            SchedulingActivationJob(
                connectionRepository = connectionRepository,
                schedulingService = schedulingService,
                schedulingAvailableNotificationService = schedulingAvailableNotificationService
            ).processSchedulingActivations()

        assertEquals(0, summary.processed)
        assertEquals(ConnectionState.SCHEDULING_PENDING, connectionRepository.findById(connection.id).orElseThrow().state)
        assertNull(schedulingService.findNegotiationOrNull(connection.id))
    }

    @Test
    fun `scheduling activation job initializes missing negotiation for already activated connection`() {
        val setup = createMatchInVisualPhase()

        visualReviewService.recordDecision(setup.matchId, setup.userAId, VisualDecision.APPROVED)
        visualReviewService.recordDecision(setup.matchId, setup.userBId, VisualDecision.APPROVED)

        val connection = connectionRepository.findByMatchId(setup.matchId)
            ?: error("Connection was not created")

        connectionRepository.updateSchedulingAvailableAt(
            connectionId = connection.id,
            availableAt = OffsetDateTime.now().minusSeconds(1)
        )
        connectionService.activateScheduling(connection.id)

        assertEquals(
            ConnectionState.SCHEDULING_PHASE,
            connectionRepository.findById(connection.id).orElseThrow().state
        )
        assertNull(schedulingService.findNegotiationOrNull(connection.id))

        SchedulingActivationJob(
            connectionRepository = connectionRepository,
            schedulingService = schedulingService,
            schedulingAvailableNotificationService = schedulingAvailableNotificationService
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
    fun `explicit join materializes active chat when scheduled window is open`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = slot,
            expectedRoundNumber = 1
        )
        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            proposedDateTime = slot,
            expectedRoundNumber = 1
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

        assertNull(
            chatRepository.findByConnectionIdAndChatType(
                setup.connectionId,
                ChatType.SECOND_CHAT
            )
        )

        val joined =
            secondChatLifecycleService.joinSecondChat(
                connectionId = setup.connectionId,
                userId = setup.userAId
            )
        val activeChat = chatRepository.findById(joined.chatId!!).orElseThrow()

        assertEquals(ChatStatus.ACTIVE, activeChat.status)
        assertNotNull(activeChat.activatedAt)
        assertEquals(
            activeChat.availableAt?.plusMinutes(120)?.toInstant(),
            activeChat.timeoutAt.toInstant()
        )
        assertEquals(
            ConnectionState.SECOND_CHAT,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )

        val repeated =
            secondChatLifecycleService.joinSecondChat(
                setup.connectionId,
                setup.userBId
            )
        assertEquals(activeChat.id, repeated.chatId)
        assertEquals(
            1,
            chatRepository.findAll().count {
                it.connectionId == setup.connectionId && it.chatType == ChatType.SECOND_CHAT
            }
        )
    }

    @Test
    fun `get second chat fails before confirmed time without creating chat`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = slot,
            expectedRoundNumber = 1
        )
        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            proposedDateTime = slot,
            expectedRoundNumber = 1
        )

        val confirmedDateTime = OffsetDateTime.now().plusMinutes(1)
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = confirmedDateTime
        )

        val error =
            assertThrows(DomainConflictException::class.java) {
                chatService.findVisibleSecondChatOrThrow(
                    connectionId = setup.connectionId,
                    userId = setup.userAId
                )
            }

        assertEquals(DomainErrorCode.SECOND_CHAT_NOT_AVAILABLE_YET, error.code)
        assertNull(
            chatRepository.findByConnectionIdAndChatType(
                setup.connectionId,
                ChatType.SECOND_CHAT
            )
        )
        assertEquals(
            ConnectionState.SECOND_CHAT_SCHEDULED,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
    }

    @Test
    fun `get second chat fails after scheduled window expired without creating chat`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = slot,
            expectedRoundNumber = 1
        )
        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            proposedDateTime = slot,
            expectedRoundNumber = 1
        )

        val confirmedDateTime = OffsetDateTime.now().minusMinutes(121)
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = confirmedDateTime
        )

        val error =
            assertThrows(DomainConflictException::class.java) {
                chatService.findVisibleSecondChatOrThrow(
                    connectionId = setup.connectionId,
                    userId = setup.userAId
                )
            }

        assertEquals(DomainErrorCode.SECOND_CHAT_EXPIRED, error.code)
        assertNull(
            chatRepository.findByConnectionIdAndChatType(
                setup.connectionId,
                ChatType.SECOND_CHAT
            )
        )
    }

    @Test
    fun `second chat join timing boundaries are authoritative`() {
        val beforeStart = createScheduledSecondChatAt(futureHalfHourSlot())
        val scheduledAt = schedulingService.findNegotiationOrThrow(beforeStart.connectionId).confirmedDateTime!!
        val beforeStartError =
            assertThrows(DomainConflictException::class.java) {
                secondChatLifecycleService.joinSecondChat(
                    connectionId = beforeStart.connectionId,
                    userId = beforeStart.userAId,
                    now = scheduledAt.minusNanos(1)
                )
            }
        assertEquals(DomainErrorCode.SECOND_CHAT_NOT_AVAILABLE_YET, beforeStartError.code)

        val atStart = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val atStartScheduledAt = schedulingService.findNegotiationOrThrow(atStart.connectionId).confirmedDateTime!!
        assertEquals(
            SecondChatAttendanceStatus.ON_TIME,
            secondChatLifecycleService.joinSecondChat(atStart.connectionId, atStart.userAId, atStartScheduledAt)
                .myAttendanceStatus
        )

        val beforeLate = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(2).withNano(0))
        val beforeLateScheduledAt = schedulingService.findNegotiationOrThrow(beforeLate.connectionId).confirmedDateTime!!
        assertEquals(
            SecondChatAttendanceStatus.ON_TIME,
            secondChatLifecycleService.joinSecondChat(
                beforeLate.connectionId,
                beforeLate.userAId,
                beforeLateScheduledAt.plusMinutes(10).minusNanos(1)
            ).myAttendanceStatus
        )

        val atLate = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(3).withNano(0))
        val atLateScheduledAt = schedulingService.findNegotiationOrThrow(atLate.connectionId).confirmedDateTime!!
        assertEquals(
            SecondChatAttendanceStatus.LATE,
            secondChatLifecycleService.joinSecondChat(
                atLate.connectionId,
                atLate.userAId,
                atLateScheduledAt.plusMinutes(10)
            ).myAttendanceStatus
        )

        val beforeClosed = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(4).withNano(0))
        val beforeClosedScheduledAt = schedulingService.findNegotiationOrThrow(beforeClosed.connectionId).confirmedDateTime!!
        assertEquals(
            SecondChatAttendanceStatus.LATE,
            secondChatLifecycleService.joinSecondChat(
                beforeClosed.connectionId,
                beforeClosed.userAId,
                beforeClosedScheduledAt.plusMinutes(20).minusNanos(1)
            ).myAttendanceStatus
        )

        val atClosed = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(5).withNano(0))
        val atClosedScheduledAt = schedulingService.findNegotiationOrThrow(atClosed.connectionId).confirmedDateTime!!
        val closedError =
            assertThrows(DomainConflictException::class.java) {
                secondChatLifecycleService.joinSecondChat(
                    atClosed.connectionId,
                    atClosed.userAId,
                    atClosedScheduledAt.plusMinutes(20)
                )
            }
        assertEquals(DomainErrorCode.SECOND_CHAT_ENTRY_CLOSED, closedError.code)
        assertEquals(
            2,
            secondChatParticipationRepository.findByConnectionId(atClosed.connectionId)
                .count { it.attendanceStatus == SecondChatAttendanceStatus.NO_SHOW }
        )
        assertEquals(ConnectionState.CLOSED, connectionRepository.findById(atClosed.connectionId).orElseThrow().state)
    }

    @Test
    fun `second chat join retries preserve original join and classification`() {
        val setup = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val scheduledAt = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime!!

        val first =
            secondChatLifecycleService.joinSecondChat(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                now = scheduledAt.plusMinutes(1)
            )
        assertNull(chatRepository.findById(first.chatId!!).orElseThrow().conversationStartedAt)
        val retry =
            secondChatLifecycleService.joinSecondChat(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                now = scheduledAt.plusMinutes(11)
            )

        assertEquals(SecondChatAttendanceStatus.ON_TIME, retry.myAttendanceStatus)
        assertEquals(first.myJoinedAt?.toInstant(), retry.myJoinedAt?.toInstant())
        assertEquals(
            scheduledAt.plusMinutes(120).toInstant(),
            chatRepository.findById(retry.chatId!!).orElseThrow().timeoutAt.toInstant()
        )
    }

    @Test
    fun `second participant join starts conversation exactly once and cancels pending claim`() {
        val setup = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val scheduledAt = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime!!
        secondChatLifecycleService.joinSecondChat(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        val claim =
            secondChatLifecycleService.createPartnerNoShowClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = scheduledAt.plusMinutes(10)
            )
        assertEquals(SecondChatResolutionRequestStatus.PENDING, claim.activeNoShowClaim?.status)

        val joined =
            secondChatLifecycleService.joinSecondChat(
                connectionId = setup.connectionId,
                userId = setup.userBId,
                now = scheduledAt.plusMinutes(11)
            )
        val conversationStartedAt = chatRepository.findById(joined.chatId!!).orElseThrow().conversationStartedAt

        assertEquals(SecondChatAttendanceStatus.LATE, joined.myAttendanceStatus)
        assertEquals(scheduledAt.plusMinutes(11).toInstant(), conversationStartedAt?.toInstant())
        assertEquals(
            SecondChatResolutionRequestStatus.CANCELLED,
            secondChatResolutionRequestRepository.findAll().single { it.connectionId == setup.connectionId }.status
        )

        secondChatLifecycleService.joinSecondChat(setup.connectionId, setup.userBId, scheduledAt.plusMinutes(12))
        assertEquals(
            conversationStartedAt?.toInstant(),
            chatRepository.findById(joined.chatId).orElseThrow().conversationStartedAt?.toInstant()
        )
    }

    @Test
    fun `second chat status and message fetch do not count as joining`() {
        val setup = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val scheduledAt = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime!!

        val status = secondChatLifecycleService.getSecondChatStatus(setup.connectionId, setup.userAId, scheduledAt)
        assertEquals(SecondChatAttendanceStatus.PENDING, status.myAttendanceStatus)
        assertNull(status.chatId)

        secondChatLifecycleService.joinSecondChat(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        val chat = chatRepository.findByConnectionIdAndChatType(setup.connectionId, ChatType.SECOND_CHAT)!!
        chatService.sendMessage(chat.id, setup.userAId, "Mensaje de espera")
        chatService.getMessages(chat.id, setup.userBId)

        val partnerStatus =
            secondChatLifecycleService.getSecondChatStatus(setup.connectionId, setup.userBId, scheduledAt.plusMinutes(2))
        assertEquals(SecondChatAttendanceStatus.PENDING, partnerStatus.myAttendanceStatus)
        assertNull(partnerStatus.myJoinedAt)
    }

    @Test
    fun `manual no show claim eligibility countdown and expiry close only absent partner`() {
        val setup = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val scheduledAt = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime!!
        val earlyClaimError =
            assertThrows(DomainConflictException::class.java) {
                secondChatLifecycleService.createPartnerNoShowClaim(
                    connectionId = setup.connectionId,
                    requesterUserId = setup.userAId,
                    now = scheduledAt.plusMinutes(9).plusSeconds(59)
                )
            }
        assertEquals(DomainErrorCode.SECOND_CHAT_NO_SHOW_CLAIM_NOT_AVAILABLE, earlyClaimError.code)

        secondChatLifecycleService.joinSecondChat(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        val waitingChat = chatService.findVisibleSecondChatOrThrow(setup.connectionId, setup.userAId)
        chatService.sendMessage(waitingChat.id, setup.userAId, "Estoy esperando")
        val claim =
            secondChatLifecycleService.createPartnerNoShowClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = scheduledAt.plusMinutes(10)
            )
        assertEquals(scheduledAt.plusMinutes(11).toInstant(), claim.activeNoShowClaim?.expiresAt?.toInstant())

        secondChatLifecycleService.processExpiredNoShowClaim(
            requestId = claim.activeNoShowClaim!!.id,
            now = scheduledAt.plusMinutes(11).plusSeconds(1)
        )

        val chat = chatRepository.findById(claim.chatId!!).orElseThrow()
        assertEquals(ChatStatus.ABANDONED, chat.status)
        assertEquals(ChatEndReason.SECOND_CHAT_NO_SHOW, chat.endedReason)
        assertNotNull(chat.endedAt)
        assertNotNull(chat.readOnlyUntil)
        assertEquals(1, chatService.getMessages(chat.id, setup.userAId).size)
        val sendError = assertThrows(DomainConflictException::class.java) {
            chatService.sendMessage(chat.id, setup.userAId, "No se puede escribir")
        }
        assertEquals(DomainErrorCode.CHAT_ABANDONED, sendError.code)
        assertEquals(
            SecondChatAttendanceStatus.NO_SHOW,
            secondChatParticipationRepository.findByConnectionIdAndUserId(setup.connectionId, setup.userBId)
                ?.attendanceStatus
        )
    }

    @Test
    fun `manual no show claim near hard cutoff expires at hard cutoff`() {
        val setup = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val scheduledAt = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime!!
        secondChatLifecycleService.joinSecondChat(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(19))

        val claim =
            secondChatLifecycleService.createPartnerNoShowClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = scheduledAt.plusMinutes(19).plusSeconds(30)
            )

        assertEquals(scheduledAt.plusMinutes(20).toInstant(), claim.activeNoShowClaim?.expiresAt?.toInstant())
    }

    @Test
    fun `hard cutoff resolves one absent both absent and both joined outcomes`() {
        val oneAbsent = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val oneAbsentScheduledAt = schedulingService.findNegotiationOrThrow(oneAbsent.connectionId).confirmedDateTime!!
        secondChatLifecycleService.joinSecondChat(oneAbsent.connectionId, oneAbsent.userAId, oneAbsentScheduledAt.plusMinutes(1))
        assertTrue(
            secondChatLifecycleService.resolveHardCutoffNoShow(
                oneAbsent.connectionId,
                oneAbsentScheduledAt.plusMinutes(20)
            )
        )
        assertEquals(
            SecondChatAttendanceStatus.NO_SHOW,
            secondChatParticipationRepository.findByConnectionIdAndUserId(oneAbsent.connectionId, oneAbsent.userBId)
                ?.attendanceStatus
        )
        assertEquals(
            ChatStatus.ABANDONED,
            chatRepository.findByConnectionIdAndChatType(oneAbsent.connectionId, ChatType.SECOND_CHAT)?.status
        )

        val bothAbsent = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(2).withNano(0))
        val bothAbsentScheduledAt = schedulingService.findNegotiationOrThrow(bothAbsent.connectionId).confirmedDateTime!!
        assertTrue(
            secondChatLifecycleService.resolveHardCutoffNoShow(
                bothAbsent.connectionId,
                bothAbsentScheduledAt.plusMinutes(20)
            )
        )
        assertEquals(ConnectionState.CLOSED, connectionRepository.findById(bothAbsent.connectionId).orElseThrow().state)
        assertNull(chatRepository.findByConnectionIdAndChatType(bothAbsent.connectionId, ChatType.SECOND_CHAT))

        val bothJoined = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(3).withNano(0))
        val bothJoinedScheduledAt = schedulingService.findNegotiationOrThrow(bothJoined.connectionId).confirmedDateTime!!
        secondChatLifecycleService.joinSecondChat(bothJoined.connectionId, bothJoined.userAId, bothJoinedScheduledAt.plusMinutes(1))
        secondChatLifecycleService.joinSecondChat(bothJoined.connectionId, bothJoined.userBId, bothJoinedScheduledAt.plusMinutes(11))
        assertFalse(
            secondChatLifecycleService.resolveHardCutoffNoShow(
                bothJoined.connectionId,
                bothJoinedScheduledAt.plusMinutes(20)
            )
        )
        assertEquals(
            ChatStatus.ACTIVE,
            chatRepository.findByConnectionIdAndChatType(bothJoined.connectionId, ChatType.SECOND_CHAT)?.status
        )
    }

    @Test
    fun `sending first message keeps materialized second chat active`() {
        val setup = createActiveSecondChat()

        chatService.sendMessage(
            chatId = setup.secondChatId,
            senderId = setup.userAId,
            content = "Activo el segundo chat con el primer mensaje"
        )

        val activeChat = chatService.findByIdOrThrow(setup.secondChatId)
        assertEquals(ChatStatus.ACTIVE, activeChat.status)
        assertNotNull(activeChat.activatedAt)
        assertEquals(
            ConnectionState.SECOND_CHAT,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
    }

    @Test
    fun `chat timeout job ignores active second chat`() {
        val setup = createActiveSecondChat()

        chatRepository.updateTimeoutAt(
            chatId = setup.secondChatId,
            timeoutAt = OffsetDateTime.now().minusSeconds(1)
        )

        ChatTimeoutJob(chatService).runNowForDev()

        assertEquals(ChatStatus.ACTIVE, chatRepository.findById(setup.secondChatId).orElseThrow().status)
        assertEquals(
            ConnectionState.SECOND_CHAT,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
    }

    @Test
    fun `second chat lifecycle job expires active second chat to read only without closing connection`() {
        val setup = createActiveSecondChat()
        chatService.sendMessage(setup.secondChatId, setup.userAId, "Mensaje para conservar")

        chatRepository.updateTimeoutAt(
            chatId = setup.secondChatId,
            timeoutAt = OffsetDateTime.now().minusSeconds(1)
        )

        secondChatLifecycleJob().runNowForDev()

        val secondChat = chatRepository.findById(setup.secondChatId).orElseThrow()
        assertEquals(ChatStatus.EXPIRED, secondChat.status)
        assertEquals(ChatEndReason.ABSOLUTE_TIMEOUT, secondChat.endedReason)
        assertNotNull(secondChat.endedAt)
        assertNotNull(secondChat.readOnlyUntil)
        assertTrue(secondChat.readOnlyUntil!!.isAfter(OffsetDateTime.now()))
        assertEquals(
            ConnectionState.SECOND_CHAT,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
        assertEquals(1, chatService.getMessages(setup.secondChatId, setup.userAId).size)
        val sendError = assertThrows(DomainConflictException::class.java) {
            chatService.sendMessage(setup.secondChatId, setup.userAId, "No deberia enviarse")
        }
        assertEquals(DomainErrorCode.CHAT_EXPIRED, sendError.code)
    }

    @Test
    fun `second chat lifecycle job closes read only second chat after retention`() {
        val setup = createActiveSecondChat()

        chatRepository.updateTimeoutAt(
            chatId = setup.secondChatId,
            timeoutAt = OffsetDateTime.now().minusSeconds(1)
        )
        secondChatLifecycleJob().runNowForDev()

        chatRepository.updateReadOnlyUntil(
            chatId = setup.secondChatId,
            readOnlyUntil = OffsetDateTime.now().minusSeconds(1)
        )
        secondChatLifecycleJob().runNowForDev()

        val secondChat = chatRepository.findById(setup.secondChatId).orElseThrow()
        assertEquals(ChatStatus.CLOSED, secondChat.status)
        assertEquals(ChatEndReason.SECOND_CHAT_READ_ONLY_EXPIRED, secondChat.endedReason)
        assertEquals(
            ConnectionState.CLOSED,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
        assertNoConnectionLocks(setup.userAId, setup.userBId)
        val readError = assertThrows(DomainConflictException::class.java) {
            chatService.getMessages(setup.secondChatId, setup.userAId)
        }
        assertEquals(DomainErrorCode.CHAT_NOT_AVAILABLE, readError.code)
    }

    @Test
    fun `second chat lifecycle job closes expired scheduled window without chat`() {
        val setup = createConnectionInSchedulingPhase()
        val slot = futureHalfHourSlot()

        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userAId,
            proposedDateTime = slot,
            expectedRoundNumber = 1
        )
        schedulingService.addProposal(
            connectionId = setup.connectionId,
            userId = setup.userBId,
            proposedDateTime = slot,
            expectedRoundNumber = 1
        )

        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = OffsetDateTime.now().minusMinutes(121)
        )

        secondChatLifecycleJob().runNowForDev()

        assertEquals(
            ConnectionState.CLOSED,
            connectionRepository.findById(setup.connectionId).orElseThrow().state
        )
        assertNull(
            chatRepository.findByConnectionIdAndChatType(
                setup.connectionId,
                ChatType.SECOND_CHAT
            )
        )
        assertNoConnectionLocks(setup.userAId, setup.userBId)
    }

    private fun secondChatLifecycleJob(): SecondChatLifecycleJob =
        SecondChatLifecycleJob(
            chatService = chatService,
            secondChatLifecycleService = secondChatLifecycleService,
            negotiationRepository = negotiationRepository
        )

    private fun createScheduledSecondChatAt(confirmedDateTime: OffsetDateTime): ConnectionFixture {
        val setup = createScheduledSecondChatReadyToEnter()
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = confirmedDateTime
        )
        return setup
    }
}
