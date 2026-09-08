package com.reals.backend.service.notification

import com.reals.backend.domain.MatchState
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.MatchService
import com.reals.backend.service.notification.sender.PushNotification
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

data class VisualReviewReminderProcessingResult(
    val succeeded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0
) {
    operator fun plus(other: VisualReviewReminderProcessingResult): VisualReviewReminderProcessingResult =
        VisualReviewReminderProcessingResult(
            succeeded = succeeded + other.succeeded,
            skipped = skipped + other.skipped,
            failed = failed + other.failed
        )
}

@Service
class VisualReviewReminderNotificationService(
    private val matchService: MatchService,
    private val visualReviewRepository: VisualReviewRepository,
    private val recipientPreparationService: PushRecipientPreparationService,
    private val preparedPushCommandProcessor: PreparedPushCommandProcessor,
    private val transactionTemplate: TransactionTemplate
) {

    fun processReminder(
        matchId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): VisualReviewReminderProcessingResult {
        val prepared = prepareReminder(matchId = matchId, now = now)
        val sent =
            prepared.commands
                .map { command -> sendAndPersist(command, now) }
                .fold(VisualReviewReminderProcessingResult()) { total, result -> total + result }

        return sent + VisualReviewReminderProcessingResult(skipped = prepared.skipped)
    }

    private fun prepareReminder(
        matchId: UUID,
        now: OffsetDateTime
    ): PreparedPushBatch =
        transactionTemplate.execute {
            val review = visualReviewRepository.findByMatchIdForUpdate(matchId)
                ?: return@execute PreparedPushBatch(skipped = 1)

            val reminderEligibleAt = review.reminderEligibleAt
                ?: return@execute PreparedPushBatch(skipped = 1)
            if (reminderEligibleAt.isAfter(now)) {
                return@execute PreparedPushBatch(skipped = 1)
            }
            if (now.isBefore(review.availableAt)) {
                return@execute PreparedPushBatch(skipped = 1)
            }

            val expiresAt = review.expiresAt
                ?: return@execute PreparedPushBatch(skipped = 1)
            if (!now.isBefore(expiresAt)) {
                return@execute PreparedPushBatch(skipped = 1)
            }

            val match = matchService.findByIdOrThrow(matchId)
            if (match.state != MatchState.VISUAL_PHASE) {
                return@execute PreparedPushBatch(skipped = 1)
            }

            val pendingUserIds = buildList {
                if (review.userAVisualDecision == null) {
                    add(match.userAId)
                }
                if (review.userBVisualDecision == null) {
                    add(match.userBId)
                }
            }

            if (pendingUserIds.isEmpty()) {
                return@execute PreparedPushBatch(skipped = 1)
            }

            recipientPreparationService.prepareRecipients(
                userIds = pendingUserIds,
                notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
                aggregateId = matchId,
                now = now
            ) { visualReviewReminderNotification(matchId) }
        }

    private fun sendAndPersist(
        command: PreparedPushCommand,
        now: OffsetDateTime
    ): VisualReviewReminderProcessingResult {
        return when (preparedPushCommandProcessor.process(command, now)) {
            PreparedPushCommandOutcome.SENT -> VisualReviewReminderProcessingResult(succeeded = 1)
            PreparedPushCommandOutcome.NOT_SENT,
            PreparedPushCommandOutcome.PROVIDER_EXCEPTION -> VisualReviewReminderProcessingResult(failed = 1)
        }
    }

    private fun visualReviewReminderNotification(matchId: UUID): PushNotification =
        PushNotification(
            title = "Tu revisión vence pronto",
            body = "Tenés una revisión pendiente. Entrá a Reals para completarla antes de que venza.",
            data = mapOf(
                "type" to PushNotificationType.VISUAL_REVIEW_REMINDER.name,
                "matchId" to matchId.toString()
            )
        )
}
