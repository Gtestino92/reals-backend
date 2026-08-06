package com.reals.backend.service.notification

import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.service.ChatService
import com.reals.backend.service.MatchFoundEvent
import com.reals.backend.service.MatchService
import com.reals.backend.service.notification.sender.PushNotification
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class MatchFoundNotificationService(
    private val matchService: MatchService,
    private val chatService: ChatService,
    private val deliveryPersistenceService: PushNotificationDeliveryPersistenceService,
    private val preparedPushCommandProcessor: PreparedPushCommandProcessor,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifyMatchFound(event: MatchFoundEvent) {
        try {
            val prepared = prepareMatchFound(event)
            prepared.commands.forEach { command ->
                sendAndPersist(command)
            }
        } catch (ex: Exception) {
            log.warn(
                "Failed to process match found notification for match={} chat={}",
                event.matchId,
                event.chatId,
                ex
            )
        }
    }

    private fun prepareMatchFound(
        event: MatchFoundEvent
    ): PreparedPushBatch =
        transactionTemplate.execute {
            val match = matchService.findByIdOrThrow(event.matchId)
            val chat = chatService.findByIdOrThrow(event.chatId)

            if (
                match.state != MatchState.CHAT_ACTIVE ||
                chat.matchId != match.id ||
                chat.chatType != ChatType.FIRST_CHAT ||
                chat.status != ChatStatus.ACTIVE
            ) {
                return@execute PreparedPushBatch(
                    skipped = 1,
                    eligible = false
                )
            }

            val now = OffsetDateTime.now()

            if (!now.isBefore(chat.timeoutAt)) {
                return@execute PreparedPushBatch(
                    skipped = 1,
                    eligible = false
                )
            }

            val ttlMillis = Duration.between(now, chat.timeoutAt)
                .toMillis()
                .coerceAtLeast(1L)

            val commands = mutableListOf<PreparedPushCommand>()
            var skipped = 0

            listOf(match.userAId, match.userBId)
                .distinct()
                .forEach { userId ->
                    if (
                        deliveryPersistenceService.deliveryExists(
                            userId = userId,
                            notificationType = PushNotificationType.MATCH_FOUND,
                            aggregateId = match.id
                        )
                    ) {
                        skipped += 1
                        return@forEach
                    }

                    val activeTokens =
                        deliveryPersistenceService.activeTokenSnapshots(userId)

                    if (activeTokens.isEmpty()) {
                        deliveryPersistenceService
                            .saveSkippedNoActiveTokenInCurrentTransaction(
                                userId = userId,
                                notificationType = PushNotificationType.MATCH_FOUND,
                                aggregateId = match.id,
                                now = now
                            )

                        skipped += 1
                        return@forEach
                    }

                    commands += PreparedPushCommand(
                        userId = userId,
                        notificationType = PushNotificationType.MATCH_FOUND,
                        aggregateId = match.id,
                        tokens = activeTokens,
                        notification = matchFoundNotification(
                            matchId = match.id,
                            ttlMillis = ttlMillis
                        ),
                        preparedAt = now
                    )
                }

            PreparedPushBatch(
                commands = commands,
                skipped = skipped
            )
        }

    private fun sendAndPersist(command: PreparedPushCommand) {
        try {
            preparedPushCommandProcessor.process(command)
        } catch (ex: Exception) {
            log.warn(
                "Match found push command failed for user={} match={}",
                command.userId,
                command.aggregateId,
                ex
            )
        }
    }

    private fun matchFoundNotification(
        matchId: UUID,
        ttlMillis: Long
    ): PushNotification =
        PushNotification(
            title = "Encontramos un chat",
            body = "Tu nuevo chat ya está disponible.",
            data = mapOf(
                "type" to PushNotificationType.MATCH_FOUND.name,
                "matchId" to matchId.toString()
            ),
            androidTtlMillis = ttlMillis,
            androidNotificationTag = "match-found-$matchId"
        )
}
