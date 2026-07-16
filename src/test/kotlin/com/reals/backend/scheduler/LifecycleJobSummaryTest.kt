package com.reals.backend.scheduler

import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.User
import com.reals.backend.domain.VisualReview
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.PenaltyService
import com.reals.backend.service.SchedulingService
import com.reals.backend.service.UserService
import com.reals.backend.service.notification.VisualReviewReminderNotificationService
import com.reals.backend.service.notification.VisualReviewReminderProcessingResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.OffsetDateTime
import java.util.UUID

class LifecycleJobSummaryTest {

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

    private fun anyOffsetDateTime(): OffsetDateTime {
        Mockito.any(OffsetDateTime::class.java)
        return OffsetDateTime.now()
    }

    private fun <T> eqValue(value: T): T {
        Mockito.eq(value)
        return value
    }
}
