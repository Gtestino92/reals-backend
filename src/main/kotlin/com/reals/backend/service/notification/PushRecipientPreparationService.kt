package com.reals.backend.service.notification

import com.reals.backend.domain.PushNotificationType
import com.reals.backend.service.NotificationPreferenceService
import com.reals.backend.service.notification.sender.PushNotification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class PushRecipientPreparationService(
    private val deliveryPersistenceService: PushNotificationDeliveryPersistenceService,
    private val notificationPreferenceService: NotificationPreferenceService
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun prepareRecipient(
        userId: UUID,
        notificationType: PushNotificationType,
        aggregateId: UUID,
        now: OffsetDateTime,
        notificationFactory: () -> PushNotification
    ): PreparedPushRecipient {
        if (
            deliveryPersistenceService.deliveryExists(
                userId = userId,
                notificationType = notificationType,
                aggregateId = aggregateId
            )
        ) {
            return PreparedPushRecipient(skipped = true)
        }

        if (
            !notificationPreferenceService.isAllowed(
                userId = userId,
                notificationType = notificationType
            )
        ) {
            deliveryPersistenceService.saveSkippedUserPreferenceInCurrentTransaction(
                userId = userId,
                notificationType = notificationType,
                aggregateId = aggregateId,
                now = now
            )
            return PreparedPushRecipient(skipped = true)
        }

        val activeTokens = deliveryPersistenceService.activeTokenSnapshots(userId)
        if (activeTokens.isEmpty()) {
            deliveryPersistenceService.saveSkippedNoActiveTokenInCurrentTransaction(
                userId = userId,
                notificationType = notificationType,
                aggregateId = aggregateId,
                now = now
            )
            return PreparedPushRecipient(skipped = true)
        }

        return PreparedPushRecipient(
            command = PreparedPushCommand(
                userId = userId,
                notificationType = notificationType,
                aggregateId = aggregateId,
                tokens = activeTokens,
                notification = notificationFactory(),
                preparedAt = now
            )
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun prepareRecipients(
        userIds: Collection<UUID>,
        notificationType: PushNotificationType,
        aggregateId: UUID,
        now: OffsetDateTime,
        notificationFactory: () -> PushNotification
    ): PreparedPushBatch {
        val commands = mutableListOf<PreparedPushCommand>()
        var skipped = 0

        userIds.forEach { userId ->
            val recipient =
                prepareRecipient(
                    userId = userId,
                    notificationType = notificationType,
                    aggregateId = aggregateId,
                    now = now,
                    notificationFactory = notificationFactory
                )
            recipient.command?.let { commands += it }
            if (recipient.skipped) {
                skipped += 1
            }
        }

        return PreparedPushBatch(
            commands = commands,
            skipped = skipped
        )
    }
}

data class PreparedPushRecipient(
    val command: PreparedPushCommand? = null,
    val skipped: Boolean = false
)
