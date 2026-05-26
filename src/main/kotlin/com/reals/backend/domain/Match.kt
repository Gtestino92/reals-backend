package com.reals.backend.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.*

enum class MatchState {
    CHAT_ACTIVE,
    VISUAL_PHASE,
    VISUAL_APPROVED,
    CHAT_REJECTED,
    VISUAL_REJECTED,
    EXPIRED
}

@Entity
@Table(name = "matches")
data class Match(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_a_id", nullable = false)
    val userAId: UUID,

    @Column(name = "user_b_id", nullable = false)
    val userBId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    var state: MatchState = MatchState.CHAT_ACTIVE,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
