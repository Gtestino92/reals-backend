package com.reals.backend.service

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationDelivery
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.repository.PushNotificationDeliveryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class SecondChatReminderNotificationService(
    private val connectionService: ConnectionService,
    private val pushDeviceTokenService: PushDeviceTokenService,
    private val pushNotificationSender: PushNotificationSender,
    private val pushNotificationDeliveryRepository: PushNotificationDeliveryRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifySecondChatReminder(
        connectionId: UUID,
        confirmedDateTime: OffsetDateTime,
        minutesBefore: Long
    ): Boolean {
        val connection = connectionService.findByIdOrThrow(connectionId)

        if (connection.state !in REMINDER_ELIGIBLE_STATES) {
            return false
        }

        listOf(connection.userAId, connection.userBId).forEach { userId ->
            notifyUser(
                userId = userId,
                connectionId = connectionId,
                confirmedDateTime = confirmedDateTime,
                minutesBefore = minutesBefore
            )
        }

        return true
    }

    private fun notifyUser(
        userId: UUID,
        connectionId: UUID,
        confirmedDateTime: OffsetDateTime,
        minutesBefore: Long
    ) {
        try {
            val deliveryAggregateId =
                secondChatReminderAggregateId(
                    connectionId = connectionId,
                    minutesBefore = minutesBefore
                )
            val existingDelivery =
                pushNotificationDeliveryRepository.findByUserIdAndNotificationTypeAndAggregateId(
                    userId = userId,
                    notificationType = PushNotificationType.SECOND_CHAT_REMINDER,
                    aggregateId = deliveryAggregateId
                )

            if (existingDelivery != null) {
                return
            }

            val activeTokens = pushDeviceTokenService.findActiveTokens(userId)
            val now = OffsetDateTime.now()

            if (activeTokens.isEmpty()) {
                saveDelivery(
                    userId = userId,
                    aggregateId = deliveryAggregateId,
                    status = PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN,
                    now = now
                )
                return
            }

            val sendResult =
                pushNotificationSender.sendToTokens(
                    tokens = activeTokens,
                    notification = secondChatReminderNotification(
                        connectionId = connectionId,
                        confirmedDateTime = confirmedDateTime,
                        minutesBefore = minutesBefore
                    )
                )

            sendResult.invalidTokens.forEach { token ->
                pushDeviceTokenService.disableToken(token)
            }

            if (sendResult.sent) {
                saveDelivery(
                    userId = userId,
                    aggregateId = deliveryAggregateId,
                    status = PushDeliveryStatus.SENT,
                    sentAt = now,
                    providerMessageId = sendResult.providerMessageIds.joinToString(",").ifBlank { null },
                    errorMessage = sendResult.errorMessage,
                    now = now
                )
            } else {
                saveDelivery(
                    userId = userId,
                    aggregateId = deliveryAggregateId,
                    status = PushDeliveryStatus.FAILED,
                    errorMessage = sendResult.errorMessage ?: "Push sender returned no successful deliveries",
                    now = now
                )
            }
        } catch (ex: Exception) {
            log.warn(
                "Failed to send second-chat reminder push notification for user {} and connection {}",
                userId,
                connectionId,
                ex
            )

            saveFailureBestEffort(
                userId = userId,
                connectionId = connectionId,
                minutesBefore = minutesBefore,
                errorMessage = ex.message ?: ex.javaClass.simpleName
            )
        }
    }

    private fun secondChatReminderNotification(
        connectionId: UUID,
        confirmedDateTime: OffsetDateTime,
        minutesBefore: Long
    ): PushNotification =
        PushNotification(
            title = "Tu segunda charla empieza pronto",
            body = "Tenes una segunda charla programada en $minutesBefore minutos.",
            data = mapOf(
                "type" to PushNotificationType.SECOND_CHAT_REMINDER.name,
                "connectionId" to connectionId.toString(),
                "availableAt" to confirmedDateTime.toString()
            )
        )

    private fun saveFailureBestEffort(
        userId: UUID,
        connectionId: UUID,
        minutesBefore: Long,
        errorMessage: String
    ) {
        try {
            val deliveryAggregateId =
                secondChatReminderAggregateId(
                    connectionId = connectionId,
                    minutesBefore = minutesBefore
                )
            val existingDelivery =
                pushNotificationDeliveryRepository.findByUserIdAndNotificationTypeAndAggregateId(
                    userId = userId,
                    notificationType = PushNotificationType.SECOND_CHAT_REMINDER,
                    aggregateId = deliveryAggregateId
                )

            if (existingDelivery != null) {
                return
            }

            saveDelivery(
                userId = userId,
                aggregateId = deliveryAggregateId,
                status = PushDeliveryStatus.FAILED,
                errorMessage = errorMessage,
                now = OffsetDateTime.now()
            )
        } catch (ex: Exception) {
            log.warn(
                "Failed to record failed second-chat reminder push delivery for user {} and connection {}",
                userId,
                connectionId,
                ex
            )
        }
    }

    private fun saveDelivery(
        userId: UUID,
        aggregateId: UUID,
        status: PushDeliveryStatus,
        sentAt: OffsetDateTime? = null,
        providerMessageId: String? = null,
        errorMessage: String? = null,
        now: OffsetDateTime
    ) {
        pushNotificationDeliveryRepository.save(
            PushNotificationDelivery(
                userId = userId,
                notificationType = PushNotificationType.SECOND_CHAT_REMINDER,
                aggregateId = aggregateId,
                sentAt = sentAt,
                status = status,
                providerMessageId = providerMessageId,
                errorMessage = errorMessage,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private companion object {
        val REMINDER_ELIGIBLE_STATES = setOf(
            ConnectionState.SECOND_CHAT_SCHEDULED
        )
    }
}

fun secondChatReminderAggregateId(
    connectionId: UUID,
    minutesBefore: Long
): UUID =
    UUID.nameUUIDFromBytes(
        "second-chat-reminder:$connectionId:$minutesBefore"
            .toByteArray(StandardCharsets.UTF_8)
    )
