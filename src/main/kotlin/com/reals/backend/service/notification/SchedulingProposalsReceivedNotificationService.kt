package com.reals.backend.service.notification

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.SchedulingProposalsReceivedEvent
import com.reals.backend.service.SchedulingService
import com.reals.backend.service.notification.sender.PushNotification
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

@Service
class SchedulingProposalsReceivedNotificationService(
    private val connectionService: ConnectionService,
    private val schedulingService: SchedulingService,
    private val recipientPreparationService: PushRecipientPreparationService,
    private val preparedPushCommandProcessor: PreparedPushCommandProcessor,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifyProposalsReceived(event: SchedulingProposalsReceivedEvent) {
        try {
            val prepared = prepareProposalsReceived(event)
            prepared.commands.forEach { command ->
                sendAndPersist(command)
            }
        } catch (ex: Exception) {
            log.warn(
                "Failed to process scheduling proposals received notification for connection={} recipient={} round={}",
                event.connectionId,
                event.recipientUserId,
                event.roundNumber,
                ex
            )
        }
    }

    private fun prepareProposalsReceived(event: SchedulingProposalsReceivedEvent): PreparedPushBatch =
        transactionTemplate.execute {
            val connection = connectionService.findByIdOrThrow(event.connectionId)
            val negotiation = schedulingService.findNegotiationOrThrow(event.connectionId)

            if (connection.state != ConnectionState.SCHEDULING_PHASE) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }
            if (negotiation.status != NegotiationStatus.PENDING) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }
            if (negotiation.roundNumber != event.roundNumber) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }
            if (event.triggeringUserId !in listOf(connection.userAId, connection.userBId)) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }
            if (event.recipientUserId !in listOf(connection.userAId, connection.userBId)) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }
            if (event.recipientUserId == event.triggeringUserId) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }

            val aggregateId =
                schedulingProposalsReceivedAggregateId(
                    connectionId = event.connectionId,
                    roundNumber = event.roundNumber
                )
            val now = OffsetDateTime.now()

            val recipient =
                recipientPreparationService.prepareRecipient(
                    userId = event.recipientUserId,
                    notificationType = PushNotificationType.SCHEDULING_PROPOSALS_RECEIVED,
                    aggregateId = aggregateId,
                    now = now
                ) {
                    proposalsReceivedNotification(
                        connectionId = event.connectionId,
                        matchId = connection.matchId,
                        roundNumber = event.roundNumber
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
                "Scheduling proposals received push command failed for user={} aggregate={}",
                command.userId,
                command.aggregateId,
                ex
            )
        }
    }

    private fun proposalsReceivedNotification(
        connectionId: UUID,
        matchId: UUID,
        roundNumber: Int
    ): PushNotification =
        PushNotification(
            title = "Recibiste nuevas opciones",
            body = "Hay nuevas propuestas para coordinar la segunda charla.",
            data = mapOf(
                "type" to PushNotificationType.SCHEDULING_PROPOSALS_RECEIVED.name,
                "connectionId" to connectionId.toString(),
                "matchId" to matchId.toString(),
                "roundNumber" to roundNumber.toString()
            )
        )
}
