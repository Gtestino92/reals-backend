package com.reals.backend.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

enum class NegotiationStatus {
    PENDING,
    CONFIRMED,
    FAILED
}

@Entity
@Table(name = "schedule_negotiations")
data class ScheduleNegotiation(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "connection_id", nullable = false)
    var connectionId: UUID,

    @Column(name = "round_number", nullable = false)
    var roundNumber: Int = 1,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: NegotiationStatus = NegotiationStatus.PENDING,

    @Column(name = "confirmed_date_time")
    var confirmedDateTime: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
