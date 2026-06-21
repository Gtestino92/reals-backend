package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.OffsetDateTime
import java.util.UUID

enum class PenaltyType {
    TEMPORARY_BAN,
    PERMANENT_BAN
}

@Entity
@Table(name = "penalties")
data class Penalty(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "reason", nullable = false)
    var reason: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    var type: PenaltyType = PenaltyType.TEMPORARY_BAN,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "expires_at")
    var expiresAt: OffsetDateTime?,

    @Column(name = "source_report_id")
    var sourceReportId: UUID? = null,

    @Column(name = "applied_by_user_id")
    var appliedByUserId: UUID? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true
)
