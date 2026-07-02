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

enum class SafetyReportStatus {
    PENDING,
    DISMISSED,
    CONFIRMED
}

enum class SafetyReportReason {
    INAPPROPRIATE_BEHAVIOR,
    HARASSMENT,
    OTHER
}

enum class SafetyReportContextType {
    CHAT,
    VISUAL_PROFILE,
    PERSONAL_MESSAGE,
    PROFILE_PHOTO,
    USER
}

enum class SafetyReportSource {
    USER,
    ADMIN,
    SYSTEM
}

@Entity
@Table(name = "safety_reports")
data class SafetyReport(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "reporter_user_id")
    var reporterUserId: UUID? = null,

    @Column(name = "reported_user_id", nullable = false)
    var reportedUserId: UUID,

    @Column(name = "chat_id")
    var chatId: UUID? = null,

    @Column(name = "match_id")
    var matchId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    var source: SafetyReportSource = SafetyReportSource.USER,

    @Column(name = "created_by_admin_user_id")
    var createdByAdminUserId: UUID? = null,

    @Column(name = "connection_id")
    var connectionId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "context_type", nullable = false)
    var contextType: SafetyReportContextType = SafetyReportContextType.CHAT,

    @Column(name = "context_id")
    var contextId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false)
    var reason: SafetyReportReason,

    @Column(name = "details", nullable = false)
    var details: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: SafetyReportStatus = SafetyReportStatus.PENDING,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "reviewed_at")
    var reviewedAt: OffsetDateTime? = null,

    @Column(name = "reviewed_by_user_id")
    var reviewedByUserId: UUID? = null,

    @Column(name = "verdict_notes")
    var verdictNotes: String? = null,

    @Column(name = "penalty_id")
    var penaltyId: UUID? = null
)

fun ChatExitReason.toSafetyReportReason(): SafetyReportReason =
    when (this) {
        ChatExitReason.INAPPROPRIATE_BEHAVIOR -> SafetyReportReason.INAPPROPRIATE_BEHAVIOR
        ChatExitReason.HARASSMENT -> SafetyReportReason.HARASSMENT
        ChatExitReason.NO_LONGER_INTERESTED,
        ChatExitReason.OTHER -> SafetyReportReason.OTHER
    }
