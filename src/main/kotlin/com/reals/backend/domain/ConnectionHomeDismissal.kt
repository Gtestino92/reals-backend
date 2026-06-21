package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(
    name = "connection_home_dismissals",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_connection_home_dismissal_user_connection",
            columnNames = ["user_id", "connection_id"]
        )
    ]
)
data class ConnectionHomeDismissal(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "connection_id", nullable = false)
    var connectionId: UUID,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)
