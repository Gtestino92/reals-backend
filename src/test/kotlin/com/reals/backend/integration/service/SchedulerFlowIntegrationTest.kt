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
import com.reals.backend.domain.UserReliabilityEventType
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
import com.reals.backend.service.ChatService
import com.reals.backend.service.SecondChatConversationLifecycleService
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
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@TestPropertySource(
    properties = [
        "user-reliability.enabled=true"
    ]
)
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
            joinSecondChatOrThrow(
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
            joinSecondChatOrThrow(
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
                joinSecondChatOrThrow(
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
            joinSecondChatOrThrow(atStart.connectionId, atStart.userAId, atStartScheduledAt)
                .myAttendanceStatus
        )

        val beforeLate = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(2).withNano(0))
        val beforeLateScheduledAt = schedulingService.findNegotiationOrThrow(beforeLate.connectionId).confirmedDateTime!!
        assertEquals(
            SecondChatAttendanceStatus.ON_TIME,
            joinSecondChatOrThrow(
                beforeLate.connectionId,
                beforeLate.userAId,
                beforeLateScheduledAt.plusMinutes(10).minusNanos(1)
            ).myAttendanceStatus
        )

        val atLate = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(3).withNano(0))
        val atLateScheduledAt = schedulingService.findNegotiationOrThrow(atLate.connectionId).confirmedDateTime!!
        assertEquals(
            SecondChatAttendanceStatus.LATE,
            joinSecondChatOrThrow(
                atLate.connectionId,
                atLate.userAId,
                atLateScheduledAt.plusMinutes(10)
            ).myAttendanceStatus
        )

        val beforeClosed = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(4).withNano(0))
        val beforeClosedScheduledAt = schedulingService.findNegotiationOrThrow(beforeClosed.connectionId).confirmedDateTime!!
        assertEquals(
            SecondChatAttendanceStatus.LATE,
            joinSecondChatOrThrow(
                beforeClosed.connectionId,
                beforeClosed.userAId,
                beforeClosedScheduledAt.plusMinutes(20).minusNanos(1)
            ).myAttendanceStatus
        )

        val atClosed = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(5).withNano(0))
        val atClosedScheduledAt = schedulingService.findNegotiationOrThrow(atClosed.connectionId).confirmedDateTime!!
        val closedError =
            assertThrows(DomainConflictException::class.java) {
                joinSecondChatOrThrow(
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
            joinSecondChatOrThrow(
                connectionId = setup.connectionId,
                userId = setup.userAId,
                now = scheduledAt.plusMinutes(1)
            )
        assertNull(chatRepository.findById(first.chatId!!).orElseThrow().conversationStartedAt)
        val retry =
            joinSecondChatOrThrow(
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
        joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        val claim =
            secondChatLifecycleService.createPartnerNoShowClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = scheduledAt.plusMinutes(10)
            ).view
        assertEquals(SecondChatResolutionRequestStatus.PENDING, claim.activeNoShowClaim?.status)

        val joinAt = claim.activeNoShowClaim!!.expiresAt.minusNanos(1)
        val joined =
            joinSecondChatOrThrow(
                connectionId = setup.connectionId,
                userId = setup.userBId,
                now = joinAt
            )
        val conversationStartedAt = chatRepository.findById(joined.chatId!!).orElseThrow().conversationStartedAt

        assertEquals(SecondChatAttendanceStatus.LATE, joined.myAttendanceStatus)
        assertNotNull(conversationStartedAt)
        assertEquals(
            SecondChatResolutionRequestStatus.CANCELLED,
            secondChatResolutionRequestRepository.findAll().single { it.connectionId == setup.connectionId }.status
        )
        assertFalse(
            userReliabilityEventRepository.findAll().any {
                it.userId == setup.userBId && it.eventType == UserReliabilityEventType.SECOND_CHAT_NO_SHOW
            }
        )

        joinSecondChatOrThrow(setup.connectionId, setup.userBId, scheduledAt.plusMinutes(12))
        assertEquals(
            conversationStartedAt?.toInstant(),
            chatRepository.findById(joined.chatId).orElseThrow().conversationStartedAt?.toInstant()
        )
    }

    @Test
    fun `responder join at expired claim boundary is rejected and no show is committed once`() {
        val setup = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val scheduledAt = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime!!
        joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        val claim =
            secondChatLifecycleService.createPartnerNoShowClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = scheduledAt.plusMinutes(10)
            ).view.activeNoShowClaim!!

        val rejected =
            rejectSecondChatJoin(
                connectionId = setup.connectionId,
                userId = setup.userBId,
                now = claim.expiresAt
            )

        assertEquals(DomainErrorCode.SECOND_CHAT_ALREADY_RESOLVED, rejected.code)
        assertEquals(
            SecondChatAttendanceStatus.NO_SHOW,
            secondChatParticipationRepository.findByConnectionIdAndUserId(setup.connectionId, setup.userBId)
                ?.attendanceStatus
        )
        assertEquals(
            SecondChatResolutionRequestStatus.COMPLETED,
            secondChatResolutionRequestRepository.findById(claim.id).orElseThrow().status
        )
        val chat = chatRepository.findByConnectionIdAndChatType(setup.connectionId, ChatType.SECOND_CHAT)!!
        assertEquals(ChatStatus.ABANDONED, chat.status)
        assertEquals(ChatEndReason.SECOND_CHAT_NO_SHOW, chat.endedReason)
        assertNull(chat.conversationStartedAt)
        assertEquals(1, noShowEventCount(setup.userBId, setup.connectionId))

        val retry =
            rejectSecondChatJoin(
                connectionId = setup.connectionId,
                userId = setup.userBId,
                now = claim.expiresAt.plusSeconds(1)
            )
        assertEquals(DomainErrorCode.SECOND_CHAT_ALREADY_RESOLVED, retry.code)
        assertFalse(secondChatLifecycleService.processExpiredNoShowClaim(claim.id, claim.expiresAt.plusSeconds(2)))
        assertEquals(1, noShowEventCount(setup.userBId, setup.connectionId))
    }

    @Test
    fun `expired claim before scheduler prevents responder from escaping no show`() {
        val setup = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val scheduledAt = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime!!
        joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        val claim =
            secondChatLifecycleService.createPartnerNoShowClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = scheduledAt.plusMinutes(10)
            ).view.activeNoShowClaim!!

        val statusBeforeResolution =
            secondChatLifecycleService.getSecondChatStatus(
                connectionId = setup.connectionId,
                userId = setup.userBId,
                now = claim.expiresAt
            )
        assertFalse(statusBeforeResolution.canJoin)
        assertFalse(statusBeforeResolution.canClaimPartnerNoShow)
        assertEquals(SecondChatResolutionRequestStatus.PENDING, statusBeforeResolution.activeNoShowClaim?.status)

        val rejected =
            rejectSecondChatJoin(setup.connectionId, setup.userBId, claim.expiresAt.plusNanos(1))

        assertEquals(DomainErrorCode.SECOND_CHAT_ALREADY_RESOLVED, rejected.code)
        assertEquals(
            SecondChatAttendanceStatus.NO_SHOW,
            secondChatParticipationRepository.findByConnectionIdAndUserId(setup.connectionId, setup.userBId)
                ?.attendanceStatus
        )
        assertEquals(
            SecondChatResolutionRequestStatus.COMPLETED,
            secondChatResolutionRequestRepository.findById(claim.id).orElseThrow().status
        )
        assertEquals(1, noShowEventCount(setup.userBId, setup.connectionId))
    }

    @Test
    fun `duplicate no show claim after expiry resolves without replacement or extension`() {
        val setup = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val scheduledAt = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime!!
        joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        val first =
            secondChatLifecycleService.createPartnerNoShowClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = scheduledAt.plusMinutes(10)
            )
        val claim = first.view.activeNoShowClaim!!

        val replay =
            secondChatLifecycleService.createPartnerNoShowClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = claim.expiresAt
            )

        assertTrue(first.created)
        assertFalse(replay.created)
        assertNull(replay.view.activeNoShowClaim)
        assertEquals(1, secondChatResolutionRequestRepository.findAll().count { it.connectionId == setup.connectionId })
        val resolved = secondChatResolutionRequestRepository.findById(claim.id).orElseThrow()
        assertEquals(SecondChatResolutionRequestStatus.COMPLETED, resolved.status)
        assertEquals(claim.expiresAt.toInstant(), resolved.expiresAt.toInstant())
        assertEquals(1, noShowEventCount(setup.userBId, setup.connectionId))

        val terminalReplay =
            secondChatLifecycleService.createPartnerNoShowClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = claim.expiresAt.plusSeconds(1)
            )
        assertFalse(terminalReplay.created)
        assertNull(terminalReplay.view.activeNoShowClaim)
        assertEquals(1, secondChatResolutionRequestRepository.findAll().count { it.connectionId == setup.connectionId })
        assertEquals(1, noShowEventCount(setup.userBId, setup.connectionId))
    }

    @Test
    fun `second chat status and message fetch do not count as joining`() {
        val setup = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val scheduledAt = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime!!

        val status = secondChatLifecycleService.getSecondChatStatus(setup.connectionId, setup.userAId, scheduledAt)
        assertEquals(SecondChatAttendanceStatus.PENDING, status.myAttendanceStatus)
        assertNull(status.chatId)

        joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
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

        joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        val waitingChat = chatService.findVisibleSecondChatOrThrow(setup.connectionId, setup.userAId)
        chatService.sendMessage(waitingChat.id, setup.userAId, "Estoy esperando")
        val claim =
            secondChatLifecycleService.createPartnerNoShowClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = scheduledAt.plusMinutes(10)
            ).view
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
        joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(19))

        val claim =
            secondChatLifecycleService.createPartnerNoShowClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = scheduledAt.plusMinutes(19).plusSeconds(30)
            ).view

        assertEquals(scheduledAt.plusMinutes(20).toInstant(), claim.activeNoShowClaim?.expiresAt?.toInstant())
    }

    @Test
    fun `hard cutoff resolves one absent both absent and both joined outcomes`() {
        val oneAbsent = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val oneAbsentScheduledAt = schedulingService.findNegotiationOrThrow(oneAbsent.connectionId).confirmedDateTime!!
        joinSecondChatOrThrow(oneAbsent.connectionId, oneAbsent.userAId, oneAbsentScheduledAt.plusMinutes(1))
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
        joinSecondChatOrThrow(bothJoined.connectionId, bothJoined.userAId, bothJoinedScheduledAt.plusMinutes(1))
        joinSecondChatOrThrow(bothJoined.connectionId, bothJoined.userBId, bothJoinedScheduledAt.plusMinutes(11))
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
    fun `mutual completion requires elapsed conversation and both participants messaging then finishes read only`() {
        val setup = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val scheduledAt = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime!!
        joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        val joined = joinSecondChatOrThrow(setup.connectionId, setup.userBId, scheduledAt.plusMinutes(2))
        val chatId = joined.chatId!!
        val conversationStartedAt = chatRepository.findById(chatId).orElseThrow().conversationStartedAt!!

        assertThrows(DomainConflictException::class.java) {
            secondChatConversationLifecycleService.createMutualCompletionRequest(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = conversationStartedAt.plusMinutes(10).minusNanos(1)
            )
        }.also { assertEquals(DomainErrorCode.SECOND_CHAT_COMPLETION_NOT_AVAILABLE, it.code) }

        sendMessageOrThrow(chatId, setup.userAId, "Yo envie primero", conversationStartedAt.plusMinutes(3))
        assertThrows(DomainConflictException::class.java) {
            secondChatConversationLifecycleService.createMutualCompletionRequest(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = conversationStartedAt.plusMinutes(10)
            )
        }.also { assertEquals(DomainErrorCode.SECOND_CHAT_COMPLETION_NOT_AVAILABLE, it.code) }

        sendMessageOrThrow(chatId, setup.userBId, "Yo tambien participe", conversationStartedAt.plusMinutes(4))
        val request =
            secondChatConversationLifecycleService.createMutualCompletionRequest(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = conversationStartedAt.plusMinutes(10)
            )
        assertTrue(request.created)
        assertEquals(SecondChatResolutionRequestStatus.PENDING, request.request?.status)
        assertEquals(conversationStartedAt.plusMinutes(11).toInstant(), request.request?.expiresAt?.toInstant())

        val replay =
            secondChatConversationLifecycleService.createMutualCompletionRequest(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = conversationStartedAt.plusMinutes(10).plusSeconds(1)
            )
        assertFalse(replay.created)
        assertEquals(request.request?.id, replay.request?.id)

        assertThrows(DomainConflictException::class.java) {
            secondChatConversationLifecycleService.decideMutualCompletion(
                connectionId = setup.connectionId,
                requestId = request.request!!.id,
                responderUserId = setup.userAId,
                decision = SecondChatConversationLifecycleService.CompletionDecision.ACCEPTED,
                now = request.request.expiresAt.minusNanos(1)
            )
        }.also { assertEquals(DomainErrorCode.SECOND_CHAT_COMPLETION_REQUEST_NOT_ACTIONABLE, it.code) }

        secondChatConversationLifecycleService.decideMutualCompletion(
            connectionId = setup.connectionId,
            requestId = request.request!!.id,
            responderUserId = setup.userBId,
            decision = SecondChatConversationLifecycleService.CompletionDecision.ACCEPTED,
            now = request.request.expiresAt.minusNanos(1)
        )

        val chat = chatRepository.findById(chatId).orElseThrow()
        assertEquals(ChatStatus.FINISHED, chat.status)
        assertEquals(ChatEndReason.SECOND_CHAT_MUTUAL_COMPLETION, chat.endedReason)
        assertNotNull(chat.readOnlyUntil)
        assertEquals(2, completionEventCount(setup.connectionId))
        assertEquals(2, chatService.getMessages(chatId, setup.userAId).size)
        assertThrows(DomainConflictException::class.java) {
            sendMessageOrThrow(chatId, setup.userAId, "No se puede escribir", request.request.expiresAt)
        }
    }

    @Test
    fun `mutual completion expiry rejection and message cancellation start requester cooldown`() {
        val setup = createMutualCompletionReadySecondChat()
        val chat = chatRepository.findByConnectionIdAndChatType(setup.connectionId, ChatType.SECOND_CHAT)!!
        val conversationStartedAt = chat.conversationStartedAt!!
        val first =
            secondChatConversationLifecycleService.createMutualCompletionRequest(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = conversationStartedAt.plusMinutes(10)
            )
        val expired =
            secondChatConversationLifecycleService.decideMutualCompletion(
                connectionId = setup.connectionId,
                requestId = first.request!!.id,
                responderUserId = setup.userBId,
                decision = SecondChatConversationLifecycleService.CompletionDecision.ACCEPTED,
                now = first.request.expiresAt
            )
        assertTrue(expired is SecondChatConversationLifecycleService.CompletionDecisionResult.Rejected)
        assertEquals(SecondChatResolutionRequestStatus.TIMED_OUT, secondChatResolutionRequestRepository.findById(first.request.id).orElseThrow().status)
        assertEquals(ChatStatus.ACTIVE, chatRepository.findById(chat.id).orElseThrow().status)
        assertEquals(0, completionEventCount(setup.connectionId))

        assertThrows(DomainConflictException::class.java) {
            secondChatConversationLifecycleService.createMutualCompletionRequest(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = first.request.expiresAt.plusSeconds(59)
            )
        }.also { assertEquals(DomainErrorCode.SECOND_CHAT_COMPLETION_REQUEST_COOLDOWN, it.code) }

        val partnerRequest =
            secondChatConversationLifecycleService.createMutualCompletionRequest(
                connectionId = setup.connectionId,
                requesterUserId = setup.userBId,
                now = first.request.expiresAt.plusSeconds(1)
            )
        assertTrue(partnerRequest.created)
        sendMessageOrThrow(chat.id, setup.userAId, "Cancelo la solicitud", partnerRequest.request!!.createdAt.plusSeconds(1))
        assertEquals(
            SecondChatResolutionRequestStatus.CANCELLED,
            secondChatResolutionRequestRepository.findById(partnerRequest.request.id).orElseThrow().status
        )
        assertEquals(setup.userAId, chatRepository.findById(chat.id).orElseThrow().lastMessageSenderId)
    }

    @Test
    fun `partner inactivity claim boundaries and exact expiry message closure`() {
        val setup = createActiveSecondChat()
        val chatId = setup.secondChatId
        val conversationStartedAt = chatRepository.findById(chatId).orElseThrow().conversationStartedAt!!
        val lastMessage = sendMessageOrThrow(chatId, setup.userAId, "Espero respuesta", conversationStartedAt.plusMinutes(1))

        assertThrows(DomainConflictException::class.java) {
            secondChatConversationLifecycleService.createPartnerInactivityClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = lastMessage.sentAt.plusMinutes(5).minusNanos(1)
            )
        }.also { assertEquals(DomainErrorCode.SECOND_CHAT_INACTIVITY_CLAIM_NOT_AVAILABLE, it.code) }
        assertThrows(DomainConflictException::class.java) {
            secondChatConversationLifecycleService.createPartnerInactivityClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userBId,
                now = lastMessage.sentAt.plusMinutes(5)
            )
        }.also { assertEquals(DomainErrorCode.SECOND_CHAT_INACTIVITY_CLAIM_NOT_AVAILABLE, it.code) }

        val claim =
            secondChatConversationLifecycleService.createPartnerInactivityClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = lastMessage.sentAt.plusMinutes(5)
            )
        assertTrue(claim.created)
        assertEquals(lastMessage.id, claim.request?.referenceMessageId)
        assertEquals(lastMessage.sentAt.plusMinutes(6).toInstant(), claim.request?.expiresAt?.toInstant())

        val rejectedMessage =
            chatService.sendMessageWithResult(
                chatId = chatId,
                senderId = setup.userBId,
                content = "Llegue tarde",
                now = claim.request!!.expiresAt
            )
        assertTrue(rejectedMessage is ChatService.SendMessageResult.RejectedAfterResolution)
        val chat = chatRepository.findById(chatId).orElseThrow()
        assertEquals(ChatStatus.ABANDONED, chat.status)
        assertEquals(ChatEndReason.SECOND_CHAT_PARTNER_INACTIVITY, chat.endedReason)
        assertEquals(SecondChatResolutionRequestStatus.COMPLETED, secondChatResolutionRequestRepository.findById(claim.request.id).orElseThrow().status)
        assertEquals(1, abandonedAfterJoinEventCount(setup.userBId, setup.connectionId))
        assertEquals(0, abandonedAfterJoinEventCount(setup.userAId, setup.connectionId))
        assertEquals(1, chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(chatId).size)
    }

    @Test
    fun `inactivity claim is cancelled by message before expiry and latest message restarts clock`() {
        val setup = createActiveSecondChat()
        val chatId = setup.secondChatId
        val conversationStartedAt = chatRepository.findById(chatId).orElseThrow().conversationStartedAt!!
        val lastMessage = sendMessageOrThrow(chatId, setup.userAId, "Espero respuesta", conversationStartedAt.plusMinutes(1))
        val claim =
            secondChatConversationLifecycleService.createPartnerInactivityClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = lastMessage.sentAt.plusMinutes(5)
            )

        val response = sendMessageOrThrow(chatId, setup.userBId, "Respondo a tiempo", claim.request!!.expiresAt.minusNanos(1))

        assertEquals(
            SecondChatResolutionRequestStatus.CANCELLED,
            secondChatResolutionRequestRepository.findById(claim.request.id).orElseThrow().status
        )
        val chat = chatRepository.findById(chatId).orElseThrow()
        assertEquals(response.sentAt.toInstant(), chat.lastMessageAt?.toInstant())
        assertEquals(setup.userBId, chat.lastMessageSenderId)
    }

    @Test
    fun `pre join waiting message clamps partner inactivity clock to conversation start`() {
        val setup = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val scheduledAt = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime!!
        val firstJoin = joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        val chatId = firstJoin.chatId!!
        val waitingMessage = sendMessageOrThrow(
            chatId = chatId,
            senderId = setup.userAId,
            content = "Te espero",
            now = scheduledAt.plusMinutes(2)
        )

        joinSecondChatOrThrow(setup.connectionId, setup.userBId, scheduledAt.plusMinutes(15))

        val joinedChat = chatRepository.findById(chatId).orElseThrow()
        val conversationStartedAt = joinedChat.conversationStartedAt!!
        assertTrue(waitingMessage.sentAt.isBefore(conversationStartedAt))
        val status =
            secondChatConversationLifecycleService.buildStatus(
                connection = connectionRepository.findById(setup.connectionId).orElseThrow(),
                chat = joinedChat,
                userId = setup.userAId,
                now = conversationStartedAt
            )
        assertEquals(conversationStartedAt.plusMinutes(5).toInstant(), status.inactivityClaimableAt?.toInstant())
        assertEquals(conversationStartedAt.plusMinutes(10).toInstant(), status.inactivityClosesAt?.toInstant())
        assertFalse(status.canClaimPartnerInactivity)
        assertThrows(DomainConflictException::class.java) {
            secondChatConversationLifecycleService.createPartnerInactivityClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = conversationStartedAt.plusMinutes(5).minusNanos(1)
            )
        }.also { assertEquals(DomainErrorCode.SECOND_CHAT_INACTIVITY_CLAIM_NOT_AVAILABLE, it.code) }
        assertFalse(
            secondChatConversationLifecycleService
                .findAutomaticInactivityDueChatIds(conversationStartedAt.plusMinutes(10).minusNanos(1), 10)
                .contains(chatId)
        )
        assertFalse(
            secondChatConversationLifecycleService.processAutomaticInactivity(
                chatId,
                conversationStartedAt.plusMinutes(10).minusNanos(1)
            )
        )

        val claim =
            secondChatConversationLifecycleService.createPartnerInactivityClaim(
                connectionId = setup.connectionId,
                requesterUserId = setup.userAId,
                now = conversationStartedAt.plusMinutes(5)
            )
        assertTrue(claim.created)
        assertEquals(waitingMessage.id, claim.request?.referenceMessageId)
        assertEquals(conversationStartedAt.plusMinutes(6).toInstant(), claim.request?.expiresAt?.toInstant())
        assertTrue(
            secondChatConversationLifecycleService
                .findAutomaticInactivityDueChatIds(conversationStartedAt.plusMinutes(10), 10)
                .contains(chatId)
        )
        assertTrue(
            secondChatConversationLifecycleService.processAutomaticInactivity(
                chatId,
                conversationStartedAt.plusMinutes(10)
            )
        )
        assertEquals(ChatEndReason.SECOND_CHAT_PARTNER_INACTIVITY, chatRepository.findById(chatId).orElseThrow().endedReason)
        assertEquals(1, abandonedAfterJoinEventCount(setup.userBId, setup.connectionId))
    }

    @Test
    fun `pre join waiting message does not block immediate partner response after joining`() {
        val setup = createScheduledSecondChatAt(OffsetDateTime.now().plusHours(1).withNano(0))
        val scheduledAt = schedulingService.findNegotiationOrThrow(setup.connectionId).confirmedDateTime!!
        val firstJoin = joinSecondChatOrThrow(setup.connectionId, setup.userAId, scheduledAt.plusMinutes(1))
        val chatId = firstJoin.chatId!!
        sendMessageOrThrow(chatId, setup.userAId, "Te espero", scheduledAt.plusMinutes(2))
        joinSecondChatOrThrow(setup.connectionId, setup.userBId, scheduledAt.plusMinutes(15))
        val conversationStartedAt = chatRepository.findById(chatId).orElseThrow().conversationStartedAt!!

        val response =
            sendMessageOrThrow(
                chatId = chatId,
                senderId = setup.userBId,
                content = "Llegue",
                now = conversationStartedAt.plusNanos(1)
            )

        val chat = chatRepository.findById(chatId).orElseThrow()
        assertEquals(ChatStatus.ACTIVE, chat.status)
        assertEquals(response.sentAt.toInstant(), chat.lastMessageAt?.toInstant())
        assertEquals(setup.userBId, chat.lastMessageSenderId)
        assertEquals(0, abandonedAfterJoinEventCount(setup.userBId, setup.connectionId))
    }

    @Test
    fun `mutual completion decision loses to due partner inactivity`() {
        val setup = createActiveSecondChat()
        val chat = chatRepository.findById(setup.secondChatId).orElseThrow()
        val conversationStartedAt = chat.conversationStartedAt!!
        sendMessageOrThrow(setup.secondChatId, setup.userAId, "Mensaje A", conversationStartedAt.plusSeconds(30))
        val latest = sendMessageOrThrow(setup.secondChatId, setup.userBId, "Mensaje B", conversationStartedAt.plusMinutes(1))
        val request =
            secondChatConversationLifecycleService.createMutualCompletionRequest(
                connectionId = setup.connectionId,
                requesterUserId = setup.userBId,
                now = conversationStartedAt.plusMinutes(10).plusSeconds(30)
            )

        val result =
            secondChatConversationLifecycleService.decideMutualCompletion(
                connectionId = setup.connectionId,
                requestId = request.request!!.id,
                responderUserId = setup.userAId,
                decision = SecondChatConversationLifecycleService.CompletionDecision.ACCEPTED,
                now = latest.sentAt.plusMinutes(10).plusSeconds(5)
            )

        assertTrue(result is SecondChatConversationLifecycleService.CompletionDecisionResult.Rejected)
        assertEquals(
            SecondChatResolutionRequestStatus.CANCELLED,
            secondChatResolutionRequestRepository.findById(request.request.id).orElseThrow().status
        )
        val resolvedChat = chatRepository.findById(setup.secondChatId).orElseThrow()
        assertEquals(ChatStatus.ABANDONED, resolvedChat.status)
        assertEquals(ChatEndReason.SECOND_CHAT_PARTNER_INACTIVITY, resolvedChat.endedReason)
        assertEquals(1, abandonedAfterJoinEventCount(setup.userAId, setup.connectionId))
        assertEquals(0, completionEventCount(setup.connectionId))
        assertFalse(secondChatConversationLifecycleService.processAutomaticInactivity(setup.secondChatId, latest.sentAt.plusMinutes(11)))
        assertEquals(1, abandonedAfterJoinEventCount(setup.userAId, setup.connectionId))
    }

    @Test
    fun `mutual completion decision times out expired request when inactivity is already due`() {
        val setup = createActiveSecondChat()
        val chat = chatRepository.findById(setup.secondChatId).orElseThrow()
        val conversationStartedAt = chat.conversationStartedAt!!
        sendMessageOrThrow(setup.secondChatId, setup.userAId, "Mensaje A", conversationStartedAt.plusSeconds(30))
        val latest = sendMessageOrThrow(setup.secondChatId, setup.userBId, "Mensaje B", conversationStartedAt.plusMinutes(1))
        val request =
            secondChatConversationLifecycleService.createMutualCompletionRequest(
                connectionId = setup.connectionId,
                requesterUserId = setup.userBId,
                now = conversationStartedAt.plusMinutes(10).plusSeconds(30)
            )

        val result =
            secondChatConversationLifecycleService.decideMutualCompletion(
                connectionId = setup.connectionId,
                requestId = request.request!!.id,
                responderUserId = setup.userAId,
                decision = SecondChatConversationLifecycleService.CompletionDecision.ACCEPTED,
                now = request.request.expiresAt.plusNanos(1)
            )

        assertTrue(result is SecondChatConversationLifecycleService.CompletionDecisionResult.Rejected)
        assertEquals(
            SecondChatResolutionRequestStatus.TIMED_OUT,
            secondChatResolutionRequestRepository.findById(request.request.id).orElseThrow().status
        )
        assertEquals(ChatEndReason.SECOND_CHAT_PARTNER_INACTIVITY, chatRepository.findById(setup.secondChatId).orElseThrow().endedReason)
        assertEquals(1, abandonedAfterJoinEventCount(setup.userAId, setup.connectionId))
        assertEquals(0, completionEventCount(setup.connectionId))
        assertFalse(secondChatConversationLifecycleService.processAutomaticInactivity(setup.secondChatId, latest.sentAt.plusMinutes(11)))
    }

    @Test
    fun `status suppresses conversation actions when lifecycle deadlines are due without mutating`() {
        val inactive = createActiveSecondChat()
        val inactiveChat = chatRepository.findById(inactive.secondChatId).orElseThrow()
        val inactiveStartedAt = inactiveChat.conversationStartedAt!!
        val latest = sendMessageOrThrow(inactive.secondChatId, inactive.userAId, "No respondio", inactiveStartedAt.plusMinutes(1))
        val connection = connectionRepository.findById(inactive.connectionId).orElseThrow()

        val before =
            secondChatConversationLifecycleService.buildStatus(
                connection = connection,
                chat = chatRepository.findById(inactive.secondChatId).orElseThrow(),
                userId = inactive.userAId,
                now = latest.sentAt.plusMinutes(5)
            )
        assertTrue(before.canClaimPartnerInactivity)
        assertFalse(before.canRequestMutualCompletion)
        assertTrue(
            secondChatConversationLifecycleService.buildStatus(
                connection = connection,
                chat = chatRepository.findById(inactive.secondChatId).orElseThrow(),
                userId = inactive.userBId,
                now = latest.sentAt.plusMinutes(5)
            ).mustRespondToPartner
        )

        val due =
            secondChatConversationLifecycleService.buildStatus(
                connection = connection,
                chat = chatRepository.findById(inactive.secondChatId).orElseThrow(),
                userId = inactive.userAId,
                now = latest.sentAt.plusMinutes(10)
            )
        assertFalse(due.canClaimPartnerInactivity)
        assertFalse(due.canRequestMutualCompletion)
        assertFalse(
            secondChatConversationLifecycleService.buildStatus(
                connection = connection,
                chat = chatRepository.findById(inactive.secondChatId).orElseThrow(),
                userId = inactive.userBId,
                now = latest.sentAt.plusMinutes(10)
            ).mustRespondToPartner
        )
        assertEquals(ChatStatus.ACTIVE, chatRepository.findById(inactive.secondChatId).orElseThrow().status)

        val silent = createActiveSecondChat()
        val silentConnection = connectionRepository.findById(silent.connectionId).orElseThrow()
        val silentChat = chatRepository.findById(silent.secondChatId).orElseThrow()
        val silentDue =
            secondChatConversationLifecycleService.buildStatus(
                connection = silentConnection,
                chat = silentChat,
                userId = silent.userAId,
                now = silentChat.conversationStartedAt!!.plusMinutes(10)
            )
        assertFalse(silentDue.canClaimPartnerInactivity)
        assertFalse(silentDue.canRequestMutualCompletion)
        assertFalse(silentDue.mustRespondToPartner)
        assertEquals(ChatStatus.ACTIVE, chatRepository.findById(silent.secondChatId).orElseThrow().status)
    }

    @Test
    fun `automatic inactivity and initial silence close read only idempotently`() {
        val inactive = createActiveSecondChat()
        val inactiveChat = chatRepository.findById(inactive.secondChatId).orElseThrow()
        val inactiveStartedAt = inactiveChat.conversationStartedAt!!
        val last = sendMessageOrThrow(inactive.secondChatId, inactive.userAId, "No respondio", inactiveStartedAt.plusMinutes(1))
        assertFalse(secondChatConversationLifecycleService.processAutomaticInactivity(inactive.secondChatId, last.sentAt.plusMinutes(10).minusNanos(1)))
        assertTrue(secondChatConversationLifecycleService.processAutomaticInactivity(inactive.secondChatId, last.sentAt.plusMinutes(10)))
        assertFalse(secondChatConversationLifecycleService.processAutomaticInactivity(inactive.secondChatId, last.sentAt.plusMinutes(11)))
        assertEquals(ChatEndReason.SECOND_CHAT_PARTNER_INACTIVITY, chatRepository.findById(inactive.secondChatId).orElseThrow().endedReason)
        assertEquals(1, abandonedAfterJoinEventCount(inactive.userBId, inactive.connectionId))
        assertEquals(0, abandonedAfterJoinEventCount(inactive.userAId, inactive.connectionId))

        val silent = createActiveSecondChat()
        val silentStartedAt = chatRepository.findById(silent.secondChatId).orElseThrow().conversationStartedAt!!
        assertFalse(secondChatConversationLifecycleService.processInitialSilence(silent.secondChatId, silentStartedAt.plusMinutes(10).minusNanos(1)))
        assertTrue(secondChatConversationLifecycleService.processInitialSilence(silent.secondChatId, silentStartedAt.plusMinutes(10)))
        assertFalse(secondChatConversationLifecycleService.processInitialSilence(silent.secondChatId, silentStartedAt.plusMinutes(11)))
        assertEquals(ChatEndReason.SECOND_CHAT_NO_CONVERSATION_STARTED, chatRepository.findById(silent.secondChatId).orElseThrow().endedReason)
        assertEquals(1, noConversationStartedEventCount(silent.userAId, silent.connectionId))
        assertEquals(1, noConversationStartedEventCount(silent.userBId, silent.connectionId))
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
            secondChatConversationLifecycleService = secondChatConversationLifecycleService,
            negotiationRepository = negotiationRepository
        )

    private fun noShowEventCount(
        userId: UUID,
        connectionId: UUID
    ): Int =
        userReliabilityEventRepository.findAll().count {
            it.userId == userId &&
                it.relatedConnectionId == connectionId &&
                it.eventType == UserReliabilityEventType.SECOND_CHAT_NO_SHOW
        }

    private fun completionEventCount(connectionId: UUID): Int =
        userReliabilityEventRepository.findAll().count {
            it.relatedConnectionId == connectionId &&
                it.eventType == UserReliabilityEventType.SECOND_CHAT_MUTUAL_COMPLETION
        }

    private fun abandonedAfterJoinEventCount(
        userId: UUID,
        connectionId: UUID
    ): Int =
        userReliabilityEventRepository.findAll().count {
            it.userId == userId &&
                it.relatedConnectionId == connectionId &&
                it.eventType == UserReliabilityEventType.SECOND_CHAT_ABANDONED_AFTER_JOIN
        }

    private fun noConversationStartedEventCount(
        userId: UUID,
        connectionId: UUID
    ): Int =
        userReliabilityEventRepository.findAll().count {
            it.userId == userId &&
                it.relatedConnectionId == connectionId &&
                it.eventType == UserReliabilityEventType.SECOND_CHAT_NO_CONVERSATION_STARTED
        }

    private fun createMutualCompletionReadySecondChat(): ActiveSecondChatFixture {
        val setup = createActiveSecondChat()
        val conversationStartedAt = chatRepository.findById(setup.secondChatId).orElseThrow().conversationStartedAt!!
        sendMessageOrThrow(setup.secondChatId, setup.userAId, "Mensaje A", conversationStartedAt.plusMinutes(1))
        sendMessageOrThrow(setup.secondChatId, setup.userBId, "Mensaje B", conversationStartedAt.plusMinutes(2))
        return setup
    }

    private fun createScheduledSecondChatAt(confirmedDateTime: OffsetDateTime): ConnectionFixture {
        val setup = createScheduledSecondChatReadyToEnter()
        negotiationRepository.updateConfirmedDateTimeByConnectionId(
            connectionId = setup.connectionId,
            confirmedDateTime = confirmedDateTime
        )
        return setup
    }
}
