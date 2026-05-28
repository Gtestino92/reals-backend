package com.reals.backend.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

enum class ProposalStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}

@Entity
@Table(name = "schedule_proposals")
data class ScheduleProposal(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "connection_id", nullable = false)
    var connectionId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "round_number", nullable = false)
    var roundNumber: Int,

    @Column(name = "preference_order", nullable = false)
    var preferenceOrder: Int,

    @Column(name = "proposed_date_time", nullable = false)
    var proposedDateTime: OffsetDateTime,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ProposalStatus = ProposalStatus.PENDING,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)
