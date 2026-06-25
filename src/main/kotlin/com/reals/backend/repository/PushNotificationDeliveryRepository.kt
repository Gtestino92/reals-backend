package com.reals.backend.repository

import com.reals.backend.domain.PushNotificationDelivery
import com.reals.backend.domain.PushNotificationType
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PushNotificationDeliveryRepository : JpaRepository<PushNotificationDelivery, UUID> {

    fun findByUserIdAndNotificationTypeAndAggregateId(
        userId: UUID,
        notificationType: PushNotificationType,
        aggregateId: UUID
    ): PushNotificationDelivery?

    fun findByNotificationTypeAndAggregateId(
        notificationType: PushNotificationType,
        aggregateId: UUID
    ): List<PushNotificationDelivery>
}
