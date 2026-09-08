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

enum class NotificationPreferenceCategory {
    ACTIVITY,
    REMINDERS,
    AVAILABILITY,
    SYSTEM
}

@Entity
@Table(
    name = "user_notification_preferences",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_user_notification_preferences_user_category",
            columnNames = ["user_id", "category"]
        )
    ]
)
data class UserNotificationPreference(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    var category: NotificationPreferenceCategory,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
