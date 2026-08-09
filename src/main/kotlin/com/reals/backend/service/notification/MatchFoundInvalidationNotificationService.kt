package com.reals.backend.service.notification

import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.service.ChatService
import com.reals.backend.service.FirstChatTerminatedEvent
import com.reals.backend.service.MatchService
import com.reals.backend.service.notification.sender.PushNotification
import com.reals.backend.service.notification.sender.PushNotificationAndroidPriority
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class MatchFoundInvalidationNotificationService(
    private val matchService: MatchService,
    private val chatService: ChatService,
    private val deliveryPersistenceService: PushNotificationDeliveryPersistenceService,
    private val preparedPushCommandProcessor: PreparedPushCommandProcessor,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifyMatchFoundInvalidated(
        event: FirstChatTerminatedEvent,
        now: OffsetDateTime = OffsetDateTime.now()
    ) {
        try {
            val prepared = prepareMatchFoundInvalidation(event, now)
            prepared.commands.forEach { command ->
                sendAndPersist(command, now)
            }
        } catch (ex: Exception) {
            log.warn(
                "Failed to process match found invalidation notification for match={} chat={}",
                event.matchId,
                event.chatId,
                ex
            )
        }
    }

    private fun prepareMatchFoundInvalidation(
        event: FirstChatTerminatedEvent,
        now: OffsetDateTime
    ): PreparedPushBatch =
        transactionTemplate.execute {
            val match = matchService.findByIdOrThrow(event.matchId)
            val chat = chatService.findByIdOrThrow(event.chatId)

            if (
                chat.id != event.chatId ||
                chat.matchId != event.matchId ||
                chat.matchId != match.id ||
                chat.chatType != ChatType.FIRST_CHAT ||
                chat.status !in TERMINAL_FIRST_CHAT_STATUSES
            ) {
                return@execute PreparedPushBatch(
                    skipped = 1,
                    eligible = false
                )
            }

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
                            notificationType = PushNotificationType.MATCH_FOUND_INVALIDATED,
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
                                notificationType = PushNotificationType.MATCH_FOUND_INVALIDATED,
                                aggregateId = match.id,
                                now = now
                            )

                        skipped += 1
                        return@forEach
                    }

                    commands += PreparedPushCommand(
                        userId = userId,
                        notificationType = PushNotificationType.MATCH_FOUND_INVALIDATED,
                        aggregateId = match.id,
                        tokens = activeTokens,
                        notification = matchFoundInvalidatedNotification(
                            matchId = match.id,
                            expiresAt = chat.timeoutAt,
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

    private fun sendAndPersist(
        command: PreparedPushCommand,
        now: OffsetDateTime
    ) {
        try {
            preparedPushCommandProcessor.process(command, now)
        } catch (ex: Exception) {
            log.warn(
                "Match found invalidation push command failed for user={} match={}",
                command.userId,
                command.aggregateId,
                ex
            )
        }
    }

    private fun matchFoundInvalidatedNotification(
        matchId: UUID,
        expiresAt: OffsetDateTime,
        ttlMillis: Long
    ): PushNotification =
        PushNotification(
            title = "Chat no disponible",
            body = "Este chat ya no está disponible.",
            data = mapOf(
                "type" to PushNotificationType.MATCH_FOUND_INVALIDATED.name,
                "matchId" to matchId.toString(),
                "expiresAt" to expiresAt.toString()
            ),
            androidTtlMillis = ttlMillis,
            includeNotificationPayload = false,
            androidPriority = PushNotificationAndroidPriority.HIGH
        )

    private companion object {
        val TERMINAL_FIRST_CHAT_STATUSES =
            setOf(
                ChatStatus.CANCELLED,
                ChatStatus.EXPIRED,
                ChatStatus.ABANDONED,
                ChatStatus.FINISHED
            )
    }
}
