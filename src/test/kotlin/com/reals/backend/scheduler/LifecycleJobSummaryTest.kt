package com.reals.backend.scheduler

import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.ScheduleNegotiation
import com.reals.backend.domain.User
import com.reals.backend.domain.VisualReview
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.PushNotificationDeliveryRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.ChatService
import com.reals.backend.service.PenaltyService
import com.reals.backend.service.SchedulingService
import com.reals.backend.service.UserService
import com.reals.backend.service.notification.SecondChatReminderNotificationService
import com.reals.backend.service.notification.SchedulingAvailableNotificationService
import com.reals.backend.service.notification.VisualReviewReminderNotificationService
import com.reals.backend.service.notification.VisualReviewReminderProcessingResult
import org.springframework.data.domain.Pageable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.OffsetDateTime
import java.util.UUID

class LifecycleJobSummaryTest {

    @Test
    fun `chat timeout job processes only one bounded batch`() {
        val chatService = Mockito.mock(ChatService::class.java)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val backlog = UUID.randomUUID()

        Mockito.`when`(chatService.findTimedOutChatIds(anyOffsetDateTime(), eqValue(3)))
            .thenReturn(listOf(first, second, backlog))
        Mockito.`when`(
            chatService.endChat(
                eqValue(first),
                eqValue(ChatStatus.EXPIRED),
                eqValue(ChatEndReason.ABSOLUTE_TIMEOUT),
                Mockito.anyList()
            )
        ).thenReturn(true)
        Mockito.`when`(
            chatService.endChat(
                eqValue(second),
                eqValue(ChatStatus.EXPIRED),
                eqValue(ChatEndReason.ABSOLUTE_TIMEOUT),
                Mockito.anyList()
            )
        ).thenReturn(false)

        val summary = ChatTimeoutJob(chatService, batchSize = 2).processTimedOutChats()

        assertEquals(2, summary.processed)
        assertEquals(1, summary.succeeded)
        assertEquals(1, summary.skipped)
        assertEquals(0, summary.failed)
        Mockito.verify(chatService, Mockito.never()).endChat(
            eqValue(backlog),
            eqValue(ChatStatus.EXPIRED),
            eqValue(ChatEndReason.ABSOLUTE_TIMEOUT),
            Mockito.anyList()
        )
    }

    @Test
    fun `scheduling activation job isolates candidate failures within bounded batch`() {
        val connectionRepository = Mockito.mock(ConnectionRepository::class.java)
        val schedulingService = Mockito.mock(SchedulingService::class.java)
        val notificationService = Mockito.mock(SchedulingAvailableNotificationService::class.java)
        val failed = UUID.randomUUID()
        val changed = UUID.randomUUID()
        val backlog = UUID.randomUUID()

        Mockito.`when`(
            connectionRepository.findSchedulingActivationDueIds(
                anyConnectionState(),
                anyConnectionState(),
                anyOffsetDateTime(),
                anyPageable()
            )
        ).thenReturn(listOf(failed, changed, backlog))
        Mockito.`when`(schedulingService.activateSchedulingAndInitializeNegotiation(eqValue(failed)))
            .thenThrow(RuntimeException("simulated activation failure"))
        Mockito.`when`(schedulingService.activateSchedulingAndInitializeNegotiation(eqValue(changed)))
            .thenReturn(
                ScheduleNegotiation(
                    connectionId = changed
                )
            )

        val summary =
            SchedulingActivationJob(
                connectionRepository = connectionRepository,
                schedulingService = schedulingService,
                schedulingAvailableNotificationService = notificationService,
                batchSize = 2
            ).processSchedulingActivations()

        assertEquals(2, summary.processed)
        assertEquals(1, summary.succeeded)
        assertEquals(0, summary.skipped)
        assertEquals(1, summary.failed)
        Mockito.verify(schedulingService).activateSchedulingAndInitializeNegotiation(failed)
        Mockito.verify(schedulingService).activateSchedulingAndInitializeNegotiation(changed)
        Mockito.verify(schedulingService, Mockito.never()).activateSchedulingAndInitializeNegotiation(backlog)
        Mockito.verify(notificationService).notifySchedulingAvailable(listOf(changed))
        Mockito.verify(notificationService, Mockito.never()).notifySchedulingAvailable(listOf(failed))
    }

    @Test
    fun `second chat reminder job round robins lead times within one bounded batch`() {
        val negotiationRepository = Mockito.mock(ScheduleNegotiationRepository::class.java)
        val deliveryRepository = Mockito.mock(PushNotificationDeliveryRepository::class.java)
        val reminderService = Mockito.mock(SecondChatReminderNotificationService::class.java)
        val first120 = confirmedNegotiation(OffsetDateTime.parse("2026-07-17T14:00:00Z"))
        val second120 = confirmedNegotiation(OffsetDateTime.parse("2026-07-17T14:01:00Z"))
        val third120 = confirmedNegotiation(OffsetDateTime.parse("2026-07-17T14:02:00Z"))
        val first10 = confirmedNegotiation(OffsetDateTime.parse("2026-07-17T12:10:00Z"))
        val requestedPageSizes = mutableListOf<Int>()

        Mockito.`when`(
            deliveryRepository.findByNotificationTypeAndAggregateId(
                anyPushNotificationType(),
                anyUuid()
            )
        ).thenReturn(emptyList())
        Mockito.`when`(
            negotiationRepository.findConfirmedSecondChatReminderRecoverableForWindow(
                anyOffsetDateTime(),
                anyOffsetDateTime(),
                anyNegotiationStatus(),
                anyConnectionStates(),
                anyPageable()
            )
        ).thenAnswer { invocation ->
            val pageable = invocation.arguments[4] as Pageable
            requestedPageSizes += pageable.pageSize
            when (requestedPageSizes.size) {
                1 -> listOf(first120, second120).take(pageable.pageSize)
                2 -> listOf(first10).take(pageable.pageSize)
                3 -> listOf(second120, third120).take(pageable.pageSize)
                4 -> emptyList<ScheduleNegotiation>()
                else -> emptyList()
            }
        }

        Mockito.`when`(
            reminderService.notifySecondChatReminder(
                eqValue(first120.connectionId),
                eqValue(first120.confirmedDateTime!!),
                eqValue(120)
            )
        ).thenThrow(RuntimeException("simulated reminder failure"))
        Mockito.`when`(
            reminderService.notifySecondChatReminder(
                eqValue(first10.connectionId),
                eqValue(first10.confirmedDateTime!!),
                eqValue(10)
            )
        ).thenReturn(true)
        Mockito.`when`(
            reminderService.notifySecondChatReminder(
                eqValue(second120.connectionId),
                eqValue(second120.confirmedDateTime!!),
                eqValue(120)
            )
        ).thenReturn(true)
        Mockito.`when`(
            reminderService.notifySecondChatReminder(
                eqValue(third120.connectionId),
                eqValue(third120.confirmedDateTime!!),
                eqValue(120)
            )
        ).thenReturn(true)

        val job =
            SecondChatReminderNotificationJob(
                negotiationRepository = negotiationRepository,
                deliveryRepository = deliveryRepository,
                reminderNotificationService = reminderService,
                fixedDelayMs = 60_000,
                reminderLeadMinutes = listOf("120", "10"),
                batchSize = 2
            )

        val firstRunSummary = job.processSecondChatReminders()

        assertEquals(2, firstRunSummary.processed)
        assertEquals(1, firstRunSummary.succeeded)
        assertEquals(0, firstRunSummary.skipped)
        assertEquals(1, firstRunSummary.failed)
        assertEquals(3, requestedPageSizes.take(2).sum())
        val firstRunOrder = Mockito.inOrder(reminderService)
        firstRunOrder.verify(reminderService).notifySecondChatReminder(
            eqValue(first120.connectionId),
            eqValue(first120.confirmedDateTime!!),
            eqValue(120)
        )
        firstRunOrder.verify(reminderService).notifySecondChatReminder(
            eqValue(first10.connectionId),
            eqValue(first10.confirmedDateTime!!),
            eqValue(10)
        )
        Mockito.verify(reminderService, Mockito.never()).notifySecondChatReminder(
            eqValue(second120.connectionId),
            eqValue(second120.confirmedDateTime!!),
            eqValue(120)
        )
        Mockito.verify(reminderService, Mockito.never()).notifySecondChatReminder(
            eqValue(third120.connectionId),
            eqValue(third120.confirmedDateTime!!),
            eqValue(120)
        )

        val secondRunSummary = job.processSecondChatReminders()

        assertEquals(2, secondRunSummary.processed)
        assertEquals(2, secondRunSummary.succeeded)
        assertEquals(0, secondRunSummary.skipped)
        assertEquals(0, secondRunSummary.failed)
        assertEquals(3, requestedPageSizes.drop(2).take(2).sum())
        val totalOrder = Mockito.inOrder(reminderService)
        totalOrder.verify(reminderService).notifySecondChatReminder(
            eqValue(first120.connectionId),
            eqValue(first120.confirmedDateTime!!),
            eqValue(120)
        )
        totalOrder.verify(reminderService).notifySecondChatReminder(
            eqValue(first10.connectionId),
            eqValue(first10.confirmedDateTime!!),
            eqValue(10)
        )
        totalOrder.verify(reminderService).notifySecondChatReminder(
            eqValue(second120.connectionId),
            eqValue(second120.confirmedDateTime!!),
            eqValue(120)
        )
        totalOrder.verify(reminderService).notifySecondChatReminder(
            eqValue(third120.connectionId),
            eqValue(third120.confirmedDateTime!!),
            eqValue(120)
        )
    }

    @Test
    fun `second chat reminder job rejects batch smaller than distinct lead times`() {
        val negotiationRepository = Mockito.mock(ScheduleNegotiationRepository::class.java)
        val deliveryRepository = Mockito.mock(PushNotificationDeliveryRepository::class.java)
        val reminderService = Mockito.mock(SecondChatReminderNotificationService::class.java)

        val exception =
            assertThrows<IllegalArgumentException> {
                SecondChatReminderNotificationJob(
                    negotiationRepository = negotiationRepository,
                    deliveryRepository = deliveryRepository,
                    reminderNotificationService = reminderService,
                    fixedDelayMs = 60_000,
                    reminderLeadMinutes = listOf("120", "10"),
                    batchSize = 1
                ).processSecondChatReminders()
            }

        assertTrue(
            exception.message?.contains("batch-size must be at least the number of distinct configured reminder lead times") == true
        )
        Mockito.verifyNoInteractions(negotiationRepository, deliveryRepository, reminderService)
    }

    @Test
    fun `second chat reminder job rejects invalid lead time values`() {
        val negotiationRepository = Mockito.mock(ScheduleNegotiationRepository::class.java)
        val deliveryRepository = Mockito.mock(PushNotificationDeliveryRepository::class.java)
        val reminderService = Mockito.mock(SecondChatReminderNotificationService::class.java)

        val exception =
            assertThrows<IllegalArgumentException> {
                SecondChatReminderNotificationJob(
                    negotiationRepository = negotiationRepository,
                    deliveryRepository = deliveryRepository,
                    reminderNotificationService = reminderService,
                    fixedDelayMs = 60_000,
                    reminderLeadMinutes = listOf("120", "invalid"),
                    batchSize = 2
                ).processSecondChatReminders()
            }

        assertTrue(
            exception.message?.contains("minutes-before must contain comma-separated positive whole minutes") == true
        )
        Mockito.verifyNoInteractions(negotiationRepository, deliveryRepository, reminderService)
    }

    @Test
    fun `second chat reminder job ignores duplicate lead times`() {
        val negotiationRepository = Mockito.mock(ScheduleNegotiationRepository::class.java)
        val deliveryRepository = Mockito.mock(PushNotificationDeliveryRepository::class.java)
        val reminderService = Mockito.mock(SecondChatReminderNotificationService::class.java)

        Mockito.`when`(
            negotiationRepository.findConfirmedSecondChatReminderRecoverableForWindow(
                anyOffsetDateTime(),
                anyOffsetDateTime(),
                anyNegotiationStatus(),
                anyConnectionStates(),
                anyPageable()
            )
        ).thenReturn(emptyList())

        val summary =
            SecondChatReminderNotificationJob(
                negotiationRepository = negotiationRepository,
                deliveryRepository = deliveryRepository,
                reminderNotificationService = reminderService,
                fixedDelayMs = 60_000,
                reminderLeadMinutes = listOf("120", "120", "10"),
                batchSize = 2
            ).processSecondChatReminders(OffsetDateTime.parse("2026-07-17T12:00:00Z"))

        assertEquals(0, summary.processed)
        Mockito.verify(negotiationRepository, Mockito.times(2))
            .findConfirmedSecondChatReminderRecoverableForWindow(
                anyOffsetDateTime(),
                anyOffsetDateTime(),
                anyNegotiationStatus(),
                anyConnectionStates(),
                anyPageable()
            )
    }

    @Test
    fun `penalty expiration job counts changed skipped and failed records`() {
        val penaltyService = Mockito.mock(PenaltyService::class.java)
        val changed = expiredPenalty()
        val stale = expiredPenalty()
        val failed = expiredPenalty()

        Mockito.`when`(penaltyService.findExpiredActivePenalties(anyOffsetDateTime()))
            .thenReturn(listOf(changed, stale, failed))
        Mockito.`when`(penaltyService.expireOverduePenalty(eqValue(changed.id), anyOffsetDateTime()))
            .thenReturn(true)
        Mockito.`when`(penaltyService.expireOverduePenalty(eqValue(stale.id), anyOffsetDateTime()))
            .thenReturn(false)
        Mockito.`when`(penaltyService.expireOverduePenalty(eqValue(failed.id), anyOffsetDateTime()))
            .thenThrow(RuntimeException("simulated penalty failure"))

        val summary = PenaltyExpirationJob(penaltyService).processExpiredPenalties()

        assertEquals(3, summary.processed)
        assertEquals(1, summary.succeeded)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.failed)
        Mockito.verify(penaltyService).expireOverduePenalty(eqValue(changed.id), anyOffsetDateTime())
        Mockito.verify(penaltyService).expireOverduePenalty(eqValue(stale.id), anyOffsetDateTime())
        Mockito.verify(penaltyService).expireOverduePenalty(eqValue(failed.id), anyOffsetDateTime())
    }

    @Test
    fun `account deletion finalization job counts changed skipped and failed users`() {
        val userService = Mockito.mock(UserService::class.java)
        val changed = User(email = "changed@example.com")
        val stale = User(email = "stale@example.com")
        val failed = User(email = "failed@example.com")

        Mockito.`when`(userService.findRecoverableAccountDeletionCandidates(anyOffsetDateTime()))
            .thenReturn(listOf(changed, stale, failed))
        Mockito.`when`(userService.finalizeRecoverableAccountDeletion(eqValue(changed.id), anyOffsetDateTime()))
            .thenReturn(true)
        Mockito.`when`(userService.finalizeRecoverableAccountDeletion(eqValue(stale.id), anyOffsetDateTime()))
            .thenReturn(false)
        Mockito.`when`(userService.finalizeRecoverableAccountDeletion(eqValue(failed.id), anyOffsetDateTime()))
            .thenThrow(RuntimeException("simulated account deletion failure"))

        val summary = AccountDeletionFinalizationJob(userService).finalizeAccountDeletions()

        assertEquals(3, summary.processed)
        assertEquals(1, summary.succeeded)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.failed)
        Mockito.verify(userService).finalizeRecoverableAccountDeletion(eqValue(changed.id), anyOffsetDateTime())
        Mockito.verify(userService).finalizeRecoverableAccountDeletion(eqValue(stale.id), anyOffsetDateTime())
        Mockito.verify(userService).finalizeRecoverableAccountDeletion(eqValue(failed.id), anyOffsetDateTime())
    }

    @Test
    fun `scheduling timeout job counts changed skipped and failed connections`() {
        val connectionRepository = Mockito.mock(ConnectionRepository::class.java)
        val schedulingService = Mockito.mock(SchedulingService::class.java)
        val changed = schedulingConnection()
        val stale = schedulingConnection()
        val failed = schedulingConnection()

        Mockito.`when`(
            connectionRepository.findByStateAndSchedulingExpiresAtBefore(
                eqValue(ConnectionState.SCHEDULING_PHASE),
                anyOffsetDateTime()
            )
        ).thenReturn(listOf(changed, stale, failed))
        Mockito.`when`(schedulingService.expireNegotiation(changed.id))
            .thenReturn(true)
        Mockito.`when`(schedulingService.expireNegotiation(stale.id))
            .thenReturn(false)
        Mockito.`when`(schedulingService.expireNegotiation(failed.id))
            .thenThrow(RuntimeException("simulated scheduling failure"))

        val summary = SchedulingNegotiationTimeoutJob(
            connectionRepository = connectionRepository,
            schedulingService = schedulingService
        ).processTimedOutNegotiations()

        assertEquals(3, summary.processed)
        assertEquals(1, summary.succeeded)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.failed)
        Mockito.verify(schedulingService).expireNegotiation(changed.id)
        Mockito.verify(schedulingService).expireNegotiation(stale.id)
        Mockito.verify(schedulingService).expireNegotiation(failed.id)
    }

    @Test
    fun `visual review reminder job counts processed notification outcomes not candidate reviews`() {
        val visualReviewRepository = Mockito.mock(VisualReviewRepository::class.java)
        val visualReviewReminderNotificationService =
            Mockito.mock(VisualReviewReminderNotificationService::class.java)
        val candidate = VisualReview(
            matchId = UUID.randomUUID(),
            expiresAt = OffsetDateTime.now().plusHours(1),
            reminderEligibleAt = OffsetDateTime.now().minusMinutes(1)
        )

        Mockito.`when`(visualReviewRepository.findVisualReviewReminderCandidates(anyOffsetDateTime()))
            .thenReturn(listOf(candidate))
        Mockito.`when`(
            visualReviewReminderNotificationService.processReminder(
                eqValue(candidate.matchId),
                anyOffsetDateTime()
            )
        ).thenReturn(VisualReviewReminderProcessingResult(succeeded = 2))

        val summary = VisualReviewReminderNotificationJob(
            visualReviewRepository = visualReviewRepository,
            visualReviewReminderNotificationService = visualReviewReminderNotificationService
        ).processVisualReviewReminders()

        assertEquals(2, summary.processed)
        assertEquals(2, summary.succeeded)
        assertEquals(0, summary.skipped)
        assertEquals(0, summary.failed)
        Mockito.verify(visualReviewReminderNotificationService).processReminder(
            eqValue(candidate.matchId),
            anyOffsetDateTime()
        )
    }

    private fun expiredPenalty(): Penalty =
        Penalty(
            userId = UUID.randomUUID(),
            reason = "Expired test penalty",
            expiresAt = OffsetDateTime.now().minusMinutes(1)
        )

    private fun schedulingConnection(): Connection =
        Connection(
            matchId = UUID.randomUUID(),
            userAId = UUID.randomUUID(),
            userBId = UUID.randomUUID(),
            state = ConnectionState.SCHEDULING_PHASE,
            schedulingExpiresAt = OffsetDateTime.now().minusMinutes(1)
        )

    private fun confirmedNegotiation(confirmedDateTime: OffsetDateTime): ScheduleNegotiation =
        ScheduleNegotiation(
            connectionId = UUID.randomUUID(),
            status = NegotiationStatus.CONFIRMED,
            confirmedDateTime = confirmedDateTime
        )

    private fun anyOffsetDateTime(): OffsetDateTime {
        Mockito.any(OffsetDateTime::class.java)
        return OffsetDateTime.now()
    }

    private fun anyPageable(): Pageable {
        Mockito.any(Pageable::class.java)
        return Pageable.ofSize(1)
    }

    private fun anyConnectionState(): ConnectionState {
        Mockito.any(ConnectionState::class.java)
        return ConnectionState.SCHEDULING_PENDING
    }

    private fun anyNegotiationStatus(): NegotiationStatus {
        Mockito.any(NegotiationStatus::class.java)
        return NegotiationStatus.CONFIRMED
    }

    private fun anyPushNotificationType(): PushNotificationType {
        Mockito.any(PushNotificationType::class.java)
        return PushNotificationType.SECOND_CHAT_REMINDER
    }

    private fun anyUuid(): UUID {
        Mockito.any(UUID::class.java)
        return UUID.randomUUID()
    }

    private fun anyConnectionStates(): Collection<ConnectionState> {
        Mockito.anyCollection<ConnectionState>()
        return listOf(ConnectionState.SECOND_CHAT_SCHEDULED)
    }

    private fun <T> eqValue(value: T): T {
        Mockito.eq(value)
        return value
    }
}
