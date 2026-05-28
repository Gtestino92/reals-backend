package com.reals.backend.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.*

enum class EngagementType {
    MATCH,
    CONNECTION
}

@Entity
@Table(
    name = "active_engagement_locks",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_engagement_lock_user_engagement",
            columnNames = ["user_id", "engagement_id"]
        )
    ]
)
data class ActiveEngagementLock(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "engagement_id", nullable = false)
    var engagementId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "engagement_type", nullable = false)
    var engagementType: EngagementType,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)
