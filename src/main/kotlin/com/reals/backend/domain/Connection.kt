package com.reals.backend.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.*

enum class ConnectionState {
    SCHEDULING_PHASE,
    SECOND_CHAT,
    CLOSED
}

@Entity
@Table(name = "connections")
data class Connection(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "match_id", nullable = false)
    val matchId: UUID,

    @Column(name = "user_a_id", nullable = false)
    val userAId: UUID,

    @Column(name = "user_b_id", nullable = false)
    val userBId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    var state: ConnectionState = ConnectionState.SCHEDULING_PHASE,

    @Column(name = "scheduling_expires_at", nullable = false)
    val schedulingExpiresAt: OffsetDateTime,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
