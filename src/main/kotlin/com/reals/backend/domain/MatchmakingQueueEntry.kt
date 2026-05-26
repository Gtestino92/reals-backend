package com.reals.backend.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.*

enum class QueueStatus {
    WAITING,
    PROCESSED
}

@Entity
@Table(name = "matchmaking_queue")
data class MatchmakingQueueEntry(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: QueueStatus = QueueStatus.WAITING,

    @Column(name = "entered_at", nullable = false)
    val enteredAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "processed_at")
    var processedAt: OffsetDateTime? = null
)
