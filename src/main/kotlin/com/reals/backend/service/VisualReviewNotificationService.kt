package com.reals.backend.service

import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationDelivery
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.repository.PushNotificationDeliveryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class VisualReviewNotificationService(
    private val matchService: MatchService,
    private val pushDeviceTokenService: PushDeviceTokenService,
    private val pushNotificationSender: PushNotificationSender,
    private val pushNotificationDeliveryRepository: PushNotificationDeliveryRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifyVisualReviewAvailable(matchId: UUID) {
        try {
            val match = matchService.findByIdOrThrow(matchId)

            listOf(match.userAId, match.userBId).forEach { userId ->
                notifyUser(
                    userId = userId,
                    matchId = matchId
                )
            }
        } catch (ex: Exception) {
            log.warn(
                "Failed to process visual review push notifications for match {}: {}",
                matchId,
                ex.message,
                ex
            )
        }
    }

    private fun notifyUser(
        userId: UUID,
        matchId: UUID
    ) {
        try {
            val existingDelivery =
                pushNotificationDeliveryRepository.findByUserIdAndNotificationTypeAndAggregateId(
                    userId = userId,
                    notificationType = PushNotificationType.VISUAL_REVIEW_AVAILABLE,
                    aggregateId = matchId
                )

            if (existingDelivery != null) {
                return
            }

            val activeTokens = pushDeviceTokenService.findActiveTokens(userId)
            val now = OffsetDateTime.now()

            if (activeTokens.isEmpty()) {
                saveDelivery(
                    userId = userId,
                    matchId = matchId,
                    status = PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN,
                    now = now
                )
                return
            }

            val sendResult =
                pushNotificationSender.sendToTokens(
                    tokens = activeTokens,
                    notification = visualReviewAvailableNotification(matchId)
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
            } else {
                saveDelivery(
                    userId = userId,
                    matchId = matchId,
                    status = PushDeliveryStatus.FAILED,
                    errorMessage = sendResult.errorMessage ?: "Push sender returned no successful deliveries",
                    now = now
                )
            }
        } catch (ex: Exception) {
            log.warn(
                "Failed to send visual review push notification for user {} and match {}: {}",
                userId,
                matchId,
                ex.message,
                ex
            )

            saveFailureBestEffort(
                userId = userId,
                matchId = matchId,
                errorMessage = ex.message ?: ex.javaClass.simpleName
            )
        }
    }

    private fun visualReviewAvailableNotification(matchId: UUID): PushNotification =
        PushNotification(
            title = "Tenés una revisión disponible",
            body = "Ya podés revisar el perfil visual de una conversación reciente.",
            data = mapOf(
                "type" to PushNotificationType.VISUAL_REVIEW_AVAILABLE.name,
                "matchId" to matchId.toString()
            )
        )

    private fun saveFailureBestEffort(
        userId: UUID,
        matchId: UUID,
        errorMessage: String
    ) {
        try {
            val existingDelivery =
                pushNotificationDeliveryRepository.findByUserIdAndNotificationTypeAndAggregateId(
                    userId = userId,
                    notificationType = PushNotificationType.VISUAL_REVIEW_AVAILABLE,
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
                now = OffsetDateTime.now()
            )
        } catch (ex: Exception) {
            log.warn(
                "Failed to record failed visual review push delivery for user {} and match {}: {}",
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
                notificationType = PushNotificationType.VISUAL_REVIEW_AVAILABLE,
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
