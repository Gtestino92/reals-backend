package com.reals.backend.service.notification

import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.service.notification.sender.PushNotification
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

@Service
class SchedulingAvailableNotificationService(
    private val connectionRepository: ConnectionRepository,
    private val deliveryPersistenceService: PushNotificationDeliveryPersistenceService,
    private val preparedPushCommandProcessor: PreparedPushCommandProcessor,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifySchedulingAvailable(connectionIds: Collection<UUID>) {
        if (connectionIds.isEmpty()) {
            return
        }

        try {
            val prepared = prepareSchedulingAvailable(connectionIds)
            prepared.commands.forEach { command ->
                sendAndPersist(command)
            }
        } catch (ex: Exception) {
            log.warn(
                "Failed to process scheduling available push notifications for connections={}",
                connectionIds,
                ex
            )
        }
    }

    private fun prepareSchedulingAvailable(connectionIds: Collection<UUID>): PreparedPushBatch =
        transactionTemplate.execute {
            val actionableConnections =
                connectionRepository.findAllById(connectionIds.toSet())
                    .filter { connection -> connection.state == ConnectionState.SCHEDULING_PHASE }

            if (actionableConnections.isEmpty()) {
                return@execute PreparedPushBatch(skipped = 1)
            }

            prepareUserGroups(
                connections = actionableConnections,
                now = OffsetDateTime.now()
            )
        }

    private fun prepareUserGroups(
        connections: List<Connection>,
        now: OffsetDateTime
    ): PreparedPushBatch {
        val commands = mutableListOf<PreparedPushCommand>()
        var skipped = 0
        val connectionsByUser =
            connections
                .flatMap { connection ->
                    listOf(
                        connection.userAId to connection.id,
                        connection.userBId to connection.id
                    )
                }
                .groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second }
                )

        connectionsByUser.forEach { (userId, userConnectionIds) ->
            val aggregateId =
                schedulingAvailableAggregateId(
                    userId = userId,
                    connectionIds = userConnectionIds
                )

            if (
                deliveryPersistenceService.deliveryExists(
                    userId = userId,
                    notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
                    aggregateId = aggregateId
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
                    aggregateId = aggregateId,
                    now = now
                )
                skipped += 1
                return@forEach
            }

            commands += PreparedPushCommand(
                userId = userId,
                notificationType = PushNotificationType.SCHEDULING_AVAILABLE,
                aggregateId = aggregateId,
                tokens = activeTokens,
                notification = schedulingAvailableNotification(),
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
            preparedPushCommandProcessor.process(command)
        } catch (ex: Exception) {
            log.warn(
                "Scheduling available push command failed for user={} aggregate={}",
                command.userId,
                command.aggregateId,
                ex
            )
        }
    }

    private fun schedulingAvailableNotification(): PushNotification =
        PushNotification(
            title = "Ya podés coordinar",
            body = "Tenés coordinaciones disponibles para la segunda charla.",
            data = mapOf(
                "type" to PushNotificationType.SCHEDULING_AVAILABLE.name
            )
        )
}
