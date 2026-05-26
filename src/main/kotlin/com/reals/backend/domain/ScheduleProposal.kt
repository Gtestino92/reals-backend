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
    val id: UUID = UUID.randomUUID(),

    @Column(name = "connection_id", nullable = false)
    val connectionId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "proposed_date_time", nullable = false)
    val proposedDateTime: OffsetDateTime,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ProposalStatus = ProposalStatus.PENDING,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
