package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

enum class MatchmakingAvailabilityNotificationEpisodeStatus {
    PENDING,
    HANDLED,
    CANCELLED
}

@Entity
@Table(name = "matchmaking_availability_notification_episodes")
data class MatchmakingAvailabilityNotificationEpisode(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: MatchmakingAvailabilityNotificationEpisodeStatus = MatchmakingAvailabilityNotificationEpisodeStatus.PENDING,

    @Column(name = "next_check_at", nullable = false)
    var nextCheckAt: OffsetDateTime,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "handled_at")
    var handledAt: OffsetDateTime? = null
)
