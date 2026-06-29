package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.OffsetDateTime
import java.util.UUID

enum class UserBlockSource {
    SAFETY_REPORT,
    MANUAL,
    ADMIN
}

@Entity
@Table(
    name = "user_blocks",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_user_block_blocker_blocked",
            columnNames = ["blocker_user_id", "blocked_user_id"]
        )
    ]
)
data class UserBlock(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "blocker_user_id", nullable = false)
    var blockerUserId: UUID,

    @Column(name = "blocked_user_id", nullable = false)
    var blockedUserId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    var source: UserBlockSource,

    @Column(name = "source_report_id")
    var sourceReportId: UUID? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)
