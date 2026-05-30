package com.reals.backend.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.*

enum class ConnectionState {
    SCHEDULING_PHASE,
    SECOND_CHAT_SCHEDULED,
    SECOND_CHAT_AVAILABLE,
    SECOND_CHAT,
    CLOSED
}

@Entity
@Table(name = "connections")
data class Connection(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "match_id", nullable = false)
    var matchId: UUID,

    @Column(name = "user_a_id", nullable = false)
    var userAId: UUID,

    @Column(name = "user_b_id", nullable = false)
    var userBId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    var state: ConnectionState = ConnectionState.SCHEDULING_PHASE,

    @Column(name = "scheduling_expires_at", nullable = false)
    var schedulingExpiresAt: OffsetDateTime,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
