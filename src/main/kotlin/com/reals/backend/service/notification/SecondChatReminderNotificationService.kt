package com.reals.backend.service.notification

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.notification.sender.PushNotification
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID

@Service
class SecondChatReminderNotificationService(
    private val connectionService: ConnectionService,
    private val deliveryPersistenceService: PushNotificationDeliveryPersistenceService,
    private val notificationProviderDispatcher: NotificationProviderDispatcher,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifySecondChatReminder(
        connectionId: UUID,
        confirmedDateTime: OffsetDateTime,
        minutesBefore: Long
    ): Boolean {
        val prepared =
            prepareSecondChatReminder(
                connectionId = connectionId,
                confirmedDateTime = confirmedDateTime,
                minutesBefore = minutesBefore
            )

        prepared.commands.forEach { command ->
            sendAndPersist(command)
        }

        return prepared.eligible
    }

    private fun prepareSecondChatReminder(
        connectionId: UUID,
        confirmedDateTime: OffsetDateTime,
        minutesBefore: Long
    ): PreparedPushBatch =
        transactionTemplate.execute {
            val connection = connectionService.findByIdOrThrow(connectionId)

            if (connection.state !in REMINDER_ELIGIBLE_STATES) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }

            val deliveryAggregateId =
                secondChatReminderAggregateId(
                    connectionId = connectionId,
                    minutesBefore = minutesBefore
                )
            val commands = mutableListOf<PreparedPushCommand>()
            var skipped = 0
            val now = OffsetDateTime.now()

            listOf(connection.userAId, connection.userBId).forEach { userId ->
                if (
                    deliveryPersistenceService.deliveryExists(
                        userId = userId,
                        notificationType = PushNotificationType.SECOND_CHAT_REMINDER,
                        aggregateId = deliveryAggregateId
                    )
                ) {
                    skipped += 1
                    return@forEach
                }

                val activeTokens = deliveryPersistenceService.activeTokenSnapshots(userId)
                if (activeTokens.isEmpty()) {
                    deliveryPersistenceService.saveSkippedNoActiveTokenInCurrentTransaction(
                        userId = userId,
                        notificationType = PushNotificationType.SECOND_CHAT_REMINDER,
                        aggregateId = deliveryAggregateId,
                        now = now
                    )
                    skipped += 1
                    return@forEach
                }

                commands += PreparedPushCommand(
                    userId = userId,
                    notificationType = PushNotificationType.SECOND_CHAT_REMINDER,
                    aggregateId = deliveryAggregateId,
                    tokens = activeTokens,
                    notification = secondChatReminderNotification(
                        connectionId = connectionId,
                        confirmedDateTime = confirmedDateTime,
                        minutesBefore = minutesBefore
                    ),
                    preparedAt = now
                )
            }

            PreparedPushBatch(
                commands = commands,
                skipped = skipped,
                eligible = true
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
                "Failed to send second-chat reminder push notification for user {} and aggregate {}",
                command.userId,
                command.aggregateId,
                ex
            )

            try {
                deliveryPersistenceService.persistFailure(
                    command = command,
                    errorMessage = ex.message ?: ex.javaClass.simpleName
                )
            } catch (persistenceEx: Exception) {
                log.warn(
                    "Failed to record failed second-chat reminder push delivery for user {} and aggregate {}",
                    command.userId,
                    command.aggregateId,
                    persistenceEx
                )
            }
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
