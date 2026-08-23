package com.reals.backend.service.notification

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.SecondChatAttendanceStatus
import com.reals.backend.domain.SecondChatParticipation
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.SecondChatParticipationRepository
import com.reals.backend.service.notification.sender.PushNotification
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

data class SecondChatStartNotificationProcessingResult(
    val succeeded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0
) {
    operator fun plus(other: SecondChatStartNotificationProcessingResult): SecondChatStartNotificationProcessingResult =
        SecondChatStartNotificationProcessingResult(
            succeeded = succeeded + other.succeeded,
            skipped = skipped + other.skipped,
            failed = failed + other.failed
        )
}

@Service
class SecondChatStartNotificationService(
    private val connectionRepository: ConnectionRepository,
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val participationRepository: SecondChatParticipationRepository,
    private val deliveryPersistenceService: PushNotificationDeliveryPersistenceService,
    private val recipientPreparationService: PushRecipientPreparationService,
    private val preparedPushCommandProcessor: PreparedPushCommandProcessor,
    private val transactionTemplate: TransactionTemplate,

    @param:Value("\${chat.second-chat.on-time-window-minutes:10}")
    private val secondChatOnTimeWindowMinutes: Long = 10
) {

    fun processSecondChatStart(
        connectionId: UUID,
        now: OffsetDateTime = OffsetDateTime.now(),
        latestSendAfterStartMinutes: Long = 5
    ): SecondChatStartNotificationProcessingResult {
        val prepared =
            prepareSecondChatStart(
                connectionId = connectionId,
                now = now,
                latestSendAfterStartMinutes = latestSendAfterStartMinutes
            )
        val sent =
            prepared.commands
                .map { command -> sendAndPersist(command, now) }
                .fold(SecondChatStartNotificationProcessingResult()) { total, result -> total + result }

        return sent + SecondChatStartNotificationProcessingResult(skipped = prepared.skipped)
    }

    private fun prepareSecondChatStart(
        connectionId: UUID,
        now: OffsetDateTime,
        latestSendAfterStartMinutes: Long
    ): PreparedPushBatch =
        transactionTemplate.execute {
            require(latestSendAfterStartMinutes > 0) {
                "notifications.second-chat-start.latest-send-after-start-minutes must be positive"
            }
            require(secondChatOnTimeWindowMinutes > 0) {
                "chat.second-chat.on-time-window-minutes must be positive"
            }

            val connection = connectionRepository.findByIdForUpdate(connectionId)
                ?: return@execute PreparedPushBatch(skipped = 1, eligible = false)

            if (connection.state !in START_NOTIFICATION_ELIGIBLE_STATES) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }

            val negotiation = negotiationRepository.findByConnectionIdForUpdate(connectionId)
                ?: return@execute PreparedPushBatch(skipped = 1, eligible = false)
            val confirmedDateTime = negotiation.confirmedDateTime
            if (negotiation.status != NegotiationStatus.CONFIRMED || confirmedDateTime == null) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }
            if (now.isBefore(confirmedDateTime)) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }
            if (now.isAfter(confirmedDateTime.plusMinutes(latestSendAfterStartMinutes))) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }
            val remainingTtlMillis =
                Duration.between(
                    now,
                    confirmedDateTime.plusMinutes(secondChatOnTimeWindowMinutes)
                ).toMillis()
            if (remainingTtlMillis <= 0) {
                return@execute PreparedPushBatch(skipped = 1, eligible = false)
            }

            val aggregateId = secondChatStartedAggregateId(connectionId)
            val participationsByUserId =
                participationRepository.findByConnectionIdForUpdate(connectionId)
                    .associateBy { it.userId }
            val commands = mutableListOf<PreparedPushCommand>()
            var skipped = 0

            listOf(connection.userAId, connection.userBId).forEach { userId ->
                if (
                    deliveryPersistenceService.deliveryExists(
                        userId = userId,
                        notificationType = PushNotificationType.SECOND_CHAT_STARTED,
                        aggregateId = aggregateId
                    )
                ) {
                    skipped += 1
                    return@forEach
                }

                if (participationsByUserId[userId].hasJoined()) {
                    deliveryPersistenceService.saveSkippedAlreadyJoinedInCurrentTransaction(
                        userId = userId,
                        notificationType = PushNotificationType.SECOND_CHAT_STARTED,
                        aggregateId = aggregateId,
                        now = now
                    )
                    skipped += 1
                    return@forEach
                }

                val recipient =
                    recipientPreparationService.prepareRecipient(
                        userId = userId,
                        notificationType = PushNotificationType.SECOND_CHAT_STARTED,
                        aggregateId = aggregateId,
                        now = now
                    ) {
                        secondChatStartedNotification(
                            connectionId = connectionId,
                            matchId = connection.matchId,
                            confirmedDateTime = confirmedDateTime,
                            ttlMillis = remainingTtlMillis
                        )
                    }
                recipient.command?.let { commands += it }
                if (recipient.skipped) {
                    skipped += 1
                }
            }

            PreparedPushBatch(
                commands = commands,
                skipped = skipped,
                eligible = true
            )
        }

    private fun sendAndPersist(
        command: PreparedPushCommand,
        now: OffsetDateTime
    ): SecondChatStartNotificationProcessingResult =
        when (preparedPushCommandProcessor.process(command, now)) {
            PreparedPushCommandOutcome.SENT -> SecondChatStartNotificationProcessingResult(succeeded = 1)
            PreparedPushCommandOutcome.NOT_SENT,
            PreparedPushCommandOutcome.PROVIDER_EXCEPTION -> SecondChatStartNotificationProcessingResult(failed = 1)
        }

    private fun secondChatStartedNotification(
        connectionId: UUID,
        matchId: UUID,
        confirmedDateTime: OffsetDateTime,
        ttlMillis: Long
    ): PushNotification =
        PushNotification(
            title = "Tu segunda charla ya empezó",
            body = "Entrá ahora a Reals para sumarte.",
            data = mapOf(
                "type" to PushNotificationType.SECOND_CHAT_STARTED.name,
                "connectionId" to connectionId.toString(),
                "matchId" to matchId.toString(),
                "availableAt" to confirmedDateTime.toString()
            ),
            androidTtlMillis = ttlMillis,
            androidNotificationTag = secondChatNotificationTag(connectionId)
        )

    private fun SecondChatParticipation?.hasJoined(): Boolean =
        this != null &&
            joinedAt != null &&
            attendanceStatus in JOINED_ATTENDANCE_STATUSES

    private companion object {
        val START_NOTIFICATION_ELIGIBLE_STATES = setOf(
            ConnectionState.SECOND_CHAT_SCHEDULED,
            ConnectionState.SECOND_CHAT_AVAILABLE,
            ConnectionState.SECOND_CHAT
        )

        val JOINED_ATTENDANCE_STATUSES = setOf(
            SecondChatAttendanceStatus.ON_TIME,
            SecondChatAttendanceStatus.LATE
        )
    }
}

fun secondChatStartedAggregateId(connectionId: UUID): UUID =
    UUID.nameUUIDFromBytes(
        "second-chat-started:$connectionId"
            .toByteArray(StandardCharsets.UTF_8)
    )

fun secondChatNotificationTag(connectionId: UUID): String =
    "second-chat-$connectionId"
