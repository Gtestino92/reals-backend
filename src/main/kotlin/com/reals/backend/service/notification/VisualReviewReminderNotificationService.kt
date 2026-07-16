package com.reals.backend.service.notification

import com.reals.backend.domain.MatchState
import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationDelivery
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.repository.PushNotificationDeliveryRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.MatchService
import com.reals.backend.service.PushDeviceTokenService
import com.reals.backend.service.notification.sender.PushNotification
import com.reals.backend.service.notification.sender.PushNotificationSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
@Transactional
class VisualReviewReminderNotificationService(
    private val matchService: MatchService,
    private val visualReviewRepository: VisualReviewRepository,
    private val pushDeviceTokenService: PushDeviceTokenService,
    private val pushNotificationSender: PushNotificationSender,
    private val pushNotificationDeliveryRepository: PushNotificationDeliveryRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun processReminder(
        matchId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): VisualReviewReminderProcessingResult {
        val review = visualReviewRepository.findByMatchIdForUpdate(matchId)
            ?: return VisualReviewReminderProcessingResult(skipped = 1)

        val reminderEligibleAt = review.reminderEligibleAt
            ?: return VisualReviewReminderProcessingResult(skipped = 1)
        if (reminderEligibleAt.isAfter(now)) {
            return VisualReviewReminderProcessingResult(skipped = 1)
        }

        val expiresAt = review.expiresAt
            ?: return VisualReviewReminderProcessingResult(skipped = 1)
        if (!now.isBefore(expiresAt)) {
            return VisualReviewReminderProcessingResult(skipped = 1)
        }

        val match = matchService.findByIdOrThrow(matchId)
        if (match.state != MatchState.VISUAL_PHASE) {
            return VisualReviewReminderProcessingResult(skipped = 1)
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
            return VisualReviewReminderProcessingResult(skipped = 1)
        }

        return pendingUserIds
            .map { userId ->
                notifyUser(
                    userId = userId,
                    matchId = matchId,
                    now = now
                )
            }
            .fold(VisualReviewReminderProcessingResult()) { total, result ->
                total + result
            }
    }

    private fun notifyUser(
        userId: UUID,
        matchId: UUID,
        now: OffsetDateTime
    ): VisualReviewReminderProcessingResult {
        try {
            val existingDelivery =
                pushNotificationDeliveryRepository.findByUserIdAndNotificationTypeAndAggregateId(
                    userId = userId,
                    notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
                    aggregateId = matchId
                )

            if (existingDelivery != null) {
                return VisualReviewReminderProcessingResult(skipped = 1)
            }

            val activeTokens = pushDeviceTokenService.findActiveTokens(userId)

            if (activeTokens.isEmpty()) {
                saveDelivery(
                    userId = userId,
                    matchId = matchId,
                    status = PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN,
                    now = now
                )
                return VisualReviewReminderProcessingResult(skipped = 1)
            }

            val sendResult =
                pushNotificationSender.sendToTokens(
                    tokens = activeTokens,
                    notification = visualReviewReminderNotification(matchId)
                )

            sendResult.invalidTokens.forEach { token ->
                pushDeviceTokenService.disableToken(token)
            }

            if (sendResult.sent) {
                saveDelivery(
                    userId = userId,
                    matchId = matchId,
                    status = PushDeliveryStatus.SENT,
                    sentAt = now,
                    providerMessageId = sendResult.providerMessageIds.joinToString(",").ifBlank { null },
                    errorMessage = sendResult.errorMessage,
                    now = now
                )
                return VisualReviewReminderProcessingResult(succeeded = 1)
            }

            saveDelivery(
                userId = userId,
                matchId = matchId,
                status = PushDeliveryStatus.FAILED,
                errorMessage = sendResult.errorMessage ?: "Push sender returned no successful deliveries",
                now = now
            )
            return VisualReviewReminderProcessingResult(failed = 1)
        } catch (ex: Exception) {
            log.warn(
                "Failed to send visual review reminder push notification for user {} and match {}: {}",
                userId,
                matchId,
                ex.message,
                ex
            )

            saveFailureBestEffort(
                userId = userId,
                matchId = matchId,
                errorMessage = ex.message ?: ex.javaClass.simpleName,
                now = now
            )
            return VisualReviewReminderProcessingResult(failed = 1)
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

    private fun saveFailureBestEffort(
        userId: UUID,
        matchId: UUID,
        errorMessage: String,
        now: OffsetDateTime
    ) {
        try {
            val existingDelivery =
                pushNotificationDeliveryRepository.findByUserIdAndNotificationTypeAndAggregateId(
                    userId = userId,
                    notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
                    aggregateId = matchId
                )

            if (existingDelivery != null) {
                return
            }

            saveDelivery(
                userId = userId,
                matchId = matchId,
                status = PushDeliveryStatus.FAILED,
                errorMessage = errorMessage,
                now = now
            )
        } catch (ex: Exception) {
            log.warn(
                "Failed to record failed visual review reminder delivery for user {} and match {}: {}",
                userId,
                matchId,
                ex.message,
                ex
            )
        }
    }

    private fun saveDelivery(
        userId: UUID,
        matchId: UUID,
        status: PushDeliveryStatus,
        sentAt: OffsetDateTime? = null,
        providerMessageId: String? = null,
        errorMessage: String? = null,
        now: OffsetDateTime
    ) {
        pushNotificationDeliveryRepository.save(
            PushNotificationDelivery(
                userId = userId,
                notificationType = PushNotificationType.VISUAL_REVIEW_REMINDER,
                aggregateId = matchId,
                sentAt = sentAt,
                status = status,
                providerMessageId = providerMessageId,
                errorMessage = errorMessage,
                createdAt = now,
                updatedAt = now
            )
        )
    }
}
