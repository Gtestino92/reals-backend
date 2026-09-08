package com.reals.backend.service.notification

import com.reals.backend.domain.PushNotificationType
import com.reals.backend.service.notification.sender.PushNotification
import com.reals.backend.service.notification.sender.PushNotificationToken
import java.time.OffsetDateTime
import java.util.UUID

data class PreparedPushCommand(
    val userId: UUID,
    val notificationType: PushNotificationType,
    val aggregateId: UUID,
    val tokens: List<PushNotificationToken>,
    val notification: PushNotification,
    val preparedAt: OffsetDateTime
)

data class PreparedPushBatch(
    val commands: List<PreparedPushCommand> = emptyList(),
    val skipped: Int = 0,
    val eligible: Boolean = true
)

