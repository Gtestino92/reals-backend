package com.reals.backend.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.*

enum class EngagementType {
    MATCH,
    CONNECTION
}

@Entity
@Table(name = "active_engagement_locks")
data class ActiveEngagementLock(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "engagement_id", nullable = false)
    val engagementId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "engagement_type", nullable = false)
    val engagementType: EngagementType,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
