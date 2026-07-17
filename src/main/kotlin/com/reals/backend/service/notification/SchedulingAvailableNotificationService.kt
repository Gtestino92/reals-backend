package com.reals.backend.service.notification

import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.notification.sender.PushNotification
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

@Service
class SchedulingAvailableNotificationService(
    private val connectionService: ConnectionService,
    private val deliveryPersistenceService: PushNotificationDeliveryPersistenceService,
    private val notificationProviderDispatcher: NotificationProviderDispatcher,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifySchedulingAvailable(connectionId: UUID) {
        try {
            val prepared = prepareSchedulingAvailable(connectionId)
            prepared.commands.forEach { command ->
                sendAndPersist(command)
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

    private fun prepareSchedulingAvailable(connectionId: UUID): PreparedPushBatch =
        transactionTemplate.execute {
            val connection = connectionService.findByIdOrThrow(connectionId)

            if (connection.state != ConnectionState.SCHEDULING_PHASE) {
                return@execute PreparedPushBatch(skipped = 1)
            }

            prepareRecipients(
                connection = connection,
                now = OffsetDateTime.now()
            )
        }

    private fun prepareRecipients(
        connection: Connection,
        now: OffsetDateTime
    ): PreparedPushBatch {
        val commands = mutableListOf<PreparedPushCommand>()
        var skipped = 0

        listOf(connection.userAId, connection.userBId).forEach { userId ->
            if (
                deliveryPersistenceService.deliveryExists(
                    userId = userId,
                    notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
                    aggregateId = connection.id
                )
            ) {
                skipped += 1
                return@forEach
            }

            val activeTokens = deliveryPersistenceService.activeTokenSnapshots(userId)
            if (activeTokens.isEmpty()) {
                deliveryPersistenceService.saveSkippedNoActiveTokenInCurrentTransaction(
                    userId = userId,
                    notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
                    aggregateId = connection.id,
                    now = now
                )
                skipped += 1
                return@forEach
            }

            commands += PreparedPushCommand(
                userId = userId,
                notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
                aggregateId = connection.id,
                tokens = activeTokens,
                notification = schedulingAvailableNotification(connection),
                preparedAt = now
            )
        }

        return PreparedPushBatch(
            commands = commands,
            skipped = skipped
        )
    }

    private fun sendAndPersist(command: PreparedPushCommand) {
        try {
            val sendResult = notificationProviderDispatcher.send(command)
            deliveryPersistenceService.persistSendResult(
                command = command,
                sendResult = sendResult
            )
        } catch (ex: Exception) {
            log.warn(
                "Failed to send scheduling available push notification for user {} and connection {}: {}",
                command.userId,
                command.aggregateId,
                ex.message,
                ex
            )

            try {
                deliveryPersistenceService.persistFailure(
                    command = command,
                    errorMessage = ex.message ?: ex.javaClass.simpleName
                )
            } catch (persistenceEx: Exception) {
                log.warn(
                    "Failed to record failed scheduling available push delivery for user {} and connection {}: {}",
                    command.userId,
                    command.aggregateId,
                    persistenceEx.message,
                    persistenceEx
                )
            }
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
}
