package com.reals.backend.service.notification

import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.service.ChatAccessService
import com.reals.backend.service.MatchFoundEvent
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
class MatchFoundNotificationService(
    private val matchService: MatchService,
    private val chatAccessService: ChatAccessService,
    private val recipientPreparationService: PushRecipientPreparationService,
    private val preparedPushCommandProcessor: PreparedPushCommandProcessor,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifyMatchFound(
        event: MatchFoundEvent,
        now: OffsetDateTime = OffsetDateTime.now()
    ) {
        try {
            val prepared = prepareMatchFound(event, now)
            prepared.commands.forEach { command ->
                sendAndPersist(command, now)
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
        event: MatchFoundEvent,
        now: OffsetDateTime
    ): PreparedPushBatch =
        transactionTemplate.execute {
            val match = matchService.findByIdOrThrow(event.matchId)
            val chat = chatAccessService.findByIdOrThrow(event.chatId)

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

            if (!now.isBefore(chat.timeoutAt)) {
                return@execute PreparedPushBatch(
                    skipped = 1,
                    eligible = false
                )
            }

            val ttlMillis = Duration.between(now, chat.timeoutAt)
                .toMillis()
                .coerceAtLeast(1L)

            recipientPreparationService.prepareRecipients(
                userIds = listOf(match.userAId, match.userBId).distinct(),
                notificationType = PushNotificationType.MATCH_FOUND,
                aggregateId = match.id,
                now = now
            ) {
                matchFoundNotification(
                    matchId = match.id,
                    expiresAt = chat.timeoutAt,
                    ttlMillis = ttlMillis
                )
            }
        }

    private fun sendAndPersist(
        command: PreparedPushCommand,
        now: OffsetDateTime
    ) {
        try {
            preparedPushCommandProcessor.process(command, now)
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
        expiresAt: OffsetDateTime,
        ttlMillis: Long
    ): PushNotification =
        PushNotification(
            title = "Encontramos un chat",
            body = "Tu nuevo chat ya está disponible.",
            data = mapOf(
                "type" to PushNotificationType.MATCH_FOUND.name,
                "matchId" to matchId.toString(),
                "expiresAt" to expiresAt.toString()
            ),
            androidTtlMillis = ttlMillis,
            includeNotificationPayload = false,
            androidPriority = PushNotificationAndroidPriority.HIGH
        )
}
