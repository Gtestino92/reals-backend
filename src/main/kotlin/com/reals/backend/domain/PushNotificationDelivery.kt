package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime
import java.util.UUID

enum class PushNotificationType {
    VISUAL_REVIEW_AVAILABLE,
    VISUAL_REVIEW_REMINDER,
    SCHEDULING_AVAILABLE,
    SECOND_CHAT_REMINDER
}

enum class PushDeliveryStatus {
    SENT,
    SKIPPED_NO_ACTIVE_TOKEN,
    FAILED
}

@Entity
@Table(
    name = "push_notification_deliveries",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_push_notification_delivery",
            columnNames = ["user_id", "notification_type", "aggregate_id"]
        )
    ]
)
data class PushNotificationDelivery(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false)
    var notificationType: PushNotificationType,

    @Column(name = "aggregate_id", nullable = false)
    var aggregateId: UUID,

    @Column(name = "sent_at")
    var sentAt: OffsetDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: PushDeliveryStatus,

    @Column(name = "provider_message_id", columnDefinition = "TEXT")
    var providerMessageId: String? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
