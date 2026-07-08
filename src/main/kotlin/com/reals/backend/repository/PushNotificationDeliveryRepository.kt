package com.reals.backend.repository

import com.reals.backend.domain.PushNotificationDelivery
import com.reals.backend.domain.PushNotificationType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface PushNotificationDeliveryRepository : JpaRepository<PushNotificationDelivery, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PushNotificationDelivery d where d.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: UUID): Int

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
