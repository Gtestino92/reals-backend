package com.reals.backend.service.notification

import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationDelivery
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.repository.PushNotificationDeliveryRepository
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.PushDeviceTokenService
import com.reals.backend.service.notification.sender.PushNotification
import com.reals.backend.service.notification.sender.PushNotificationSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class SchedulingAvailableNotificationService(
    private val connectionService: ConnectionService,
    private val pushDeviceTokenService: PushDeviceTokenService,
    private val pushNotificationSender: PushNotificationSender,
    private val pushNotificationDeliveryRepository: PushNotificationDeliveryRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifySchedulingAvailable(connectionId: UUID) {
        try {
            val connection = connectionService.findByIdOrThrow(connectionId)

            if (connection.state != ConnectionState.SCHEDULING_PHASE) {
                return
            }

            listOf(connection.userAId, connection.userBId).forEach { userId ->
                notifyUser(
                    userId = userId,
                    connection = connection
                )
            }
        } catch (ex: Exception) {
            log.warn(
                "Failed to process scheduling available push notifications for connection {}: {}",
                connectionId,
                ex.message,
                ex
            )
        }
    }

    private fun notifyUser(
        userId: UUID,
        connection: Connection
    ) {
        try {
            val existingDelivery =
                pushNotificationDeliveryRepository.findByUserIdAndNotificationTypeAndAggregateId(
                    userId = userId,
                    notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
                    aggregateId = connection.id
                )

            if (existingDelivery != null) {
                return
            }

            val activeTokens = pushDeviceTokenService.findActiveTokens(userId)
            val now = OffsetDateTime.now()

            if (activeTokens.isEmpty()) {
                saveDelivery(
                    userId = userId,
                    connectionId = connection.id,
                    status = PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN,
                    now = now
                )
                return
            }

            val sendResult =
                pushNotificationSender.sendToTokens(
                    tokens = activeTokens,
                    notification = schedulingAvailableNotification(connection)
                )

            sendResult.invalidTokens.forEach { token ->
                pushDeviceTokenService.disableToken(token)
            }

            if (sendResult.sent) {
                saveDelivery(
                    userId = userId,
                    connectionId = connection.id,
                    status = PushDeliveryStatus.SENT,
                    sentAt = now,
                    providerMessageId = sendResult.providerMessageIds.joinToString(",").ifBlank { null },
                    errorMessage = sendResult.errorMessage,
                    now = now
                )
            } else {
                saveDelivery(
                    userId = userId,
                    connectionId = connection.id,
                    status = PushDeliveryStatus.FAILED,
                    errorMessage = sendResult.errorMessage ?: "Push sender returned no successful deliveries",
                    now = now
                )
            }
        } catch (ex: Exception) {
            log.warn(
                "Failed to send scheduling available push notification for user {} and connection {}: {}",
                userId,
                connection.id,
                ex.message,
                ex
            )

            saveFailureBestEffort(
                userId = userId,
                connectionId = connection.id,
                errorMessage = ex.message ?: ex.javaClass.simpleName
            )
        }
    }

    private fun schedulingAvailableNotification(connection: Connection): PushNotification =
        PushNotification(
            title = "Ya pueden coordinar horarios",
            body = "La coordinación para la segunda charla ya está disponible.",
            data = mapOf(
                "type" to PushNotificationType.SCHEDULING_AVAILABLE.name,
                "connectionId" to connection.id.toString(),
                "matchId" to connection.matchId.toString()
            )
        )

    private fun saveFailureBestEffort(
        userId: UUID,
        connectionId: UUID,
        errorMessage: String
    ) {
        try {
            val existingDelivery =
                pushNotificationDeliveryRepository.findByUserIdAndNotificationTypeAndAggregateId(
                    userId = userId,
                    notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
                    aggregateId = connectionId
                )

            if (existingDelivery != null) {
                return
            }

            saveDelivery(
                userId = userId,
                connectionId = connectionId,
                status = PushDeliveryStatus.FAILED,
                errorMessage = errorMessage,
                now = OffsetDateTime.now()
            )
        } catch (ex: Exception) {
            log.warn(
                "Failed to record failed scheduling available push delivery for user {} and connection {}: {}",
                userId,
                connectionId,
                ex.message,
                ex
            )
        }
    }

    private fun saveDelivery(
        userId: UUID,
        connectionId: UUID,
        status: PushDeliveryStatus,
        sentAt: OffsetDateTime? = null,
        providerMessageId: String? = null,
        errorMessage: String? = null,
        now: OffsetDateTime
    ) {
        pushNotificationDeliveryRepository.save(
            PushNotificationDelivery(
                userId = userId,
                notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
                aggregateId = connectionId,
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
