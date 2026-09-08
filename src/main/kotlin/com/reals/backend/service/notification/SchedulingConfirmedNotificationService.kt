package com.reals.backend.service.notification

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.SchedulingConfirmedEvent
import com.reals.backend.service.SchedulingService
import com.reals.backend.service.notification.sender.PushNotification
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

@Service
class SchedulingConfirmedNotificationService(
    private val connectionService: ConnectionService,
    private val schedulingService: SchedulingService,
    private val recipientPreparationService: PushRecipientPreparationService,
    private val preparedPushCommandProcessor: PreparedPushCommandProcessor,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifySchedulingConfirmed(event: SchedulingConfirmedEvent) {
        try {
            val prepared = prepareSchedulingConfirmed(event)
            prepared.commands.forEach { command ->
                sendAndPersist(command)
            }
        } catch (ex: Exception) {
            log.warn(
                "Failed to process scheduling confirmed notification for connection={} triggeringUser={}",
                event.connectionId,
                event.triggeringUserId,
                ex
            )
        }
    }

    private fun prepareSchedulingConfirmed(event: SchedulingConfirmedEvent): PreparedPushBatch =
        transactionTemplate.execute {
            val connection = connectionService.findByIdOrThrow(event.connectionId)
            val negotiation = schedulingService.findNegotiationOrThrow(event.connectionId)
            val confirmedDateTime = negotiation.confirmedDateTime

            if (connection.state != ConnectionState.SECOND_CHAT_SCHEDULED) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }
            if (negotiation.status != NegotiationStatus.CONFIRMED || confirmedDateTime == null) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }
            if (event.triggeringUserId !in listOf(connection.userAId, connection.userBId)) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }

            val recipientUserId =
                if (event.triggeringUserId == connection.userAId) {
                    connection.userBId
                } else {
                    connection.userAId
                }
            val aggregateId = connection.id
            val now = OffsetDateTime.now()

            val recipient =
                recipientPreparationService.prepareRecipient(
                    userId = recipientUserId,
                    notificationType = PushNotificationType.SCHEDULING_CONFIRMED,
                    aggregateId = aggregateId,
                    now = now
                ) {
                    schedulingConfirmedNotification(
                        connectionId = connection.id,
                        matchId = connection.matchId,
                        confirmedDateTime = confirmedDateTime
                    )
                }

            PreparedPushBatch(
                commands = listOfNotNull(recipient.command),
                skipped = if (recipient.skipped) 1 else 0
            )
        }

    private fun sendAndPersist(command: PreparedPushCommand) {
        try {
            preparedPushCommandProcessor.process(command)
        } catch (ex: Exception) {
            log.warn(
                "Scheduling confirmed push command failed for user={} aggregate={}",
                command.userId,
                command.aggregateId,
                ex
            )
        }
    }

    private fun schedulingConfirmedNotification(
        connectionId: UUID,
        matchId: UUID,
        confirmedDateTime: OffsetDateTime
    ): PushNotification =
        PushNotification(
            title = "La segunda charla quedó coordinada",
            body = "El horario ya está confirmado. Revisalo en la app.",
            data = mapOf(
                "type" to PushNotificationType.SCHEDULING_CONFIRMED.name,
                "connectionId" to connectionId.toString(),
                "matchId" to matchId.toString(),
                "availableAt" to confirmedDateTime.toString()
            )
        )
}
