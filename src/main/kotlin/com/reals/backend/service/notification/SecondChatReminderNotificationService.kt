package com.reals.backend.service.notification

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.notification.sender.PushNotification
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class SecondChatReminderNotificationService(
    private val connectionService: ConnectionService,
    private val recipientPreparationService: PushRecipientPreparationService,
    private val preparedPushCommandProcessor: PreparedPushCommandProcessor,
    private val transactionTemplate: TransactionTemplate
) {

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
            val now = OffsetDateTime.now()
            val remainingTtlMillis = Duration.between(now, confirmedDateTime).toMillis()
            if (remainingTtlMillis <= 0) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }

            recipientPreparationService.prepareRecipients(
                userIds = listOf(connection.userAId, connection.userBId),
                notificationType = PushNotificationType.SECOND_CHAT_REMINDER,
                aggregateId = deliveryAggregateId,
                now = now
            ) {
                secondChatReminderNotification(
                        connectionId = connectionId,
                        confirmedDateTime = confirmedDateTime,
                        minutesBefore = minutesBefore,
                        ttlMillis = remainingTtlMillis
                )
            }
                .copy(eligible = true)
        }

    private fun sendAndPersist(command: PreparedPushCommand) {
        preparedPushCommandProcessor.process(command)
    }

    private fun secondChatReminderNotification(
        connectionId: UUID,
        confirmedDateTime: OffsetDateTime,
        minutesBefore: Long,
        ttlMillis: Long
    ): PushNotification =
        PushNotification(
            title = "Tu segunda charla empieza pronto",
            body = "Tenes una segunda charla programada en $minutesBefore minutos.",
            data = mapOf(
                "type" to PushNotificationType.SECOND_CHAT_REMINDER.name,
                "connectionId" to connectionId.toString(),
                "availableAt" to confirmedDateTime.toString()
            ),
            androidTtlMillis = ttlMillis,
            androidNotificationTag = secondChatNotificationTag(connectionId)
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
