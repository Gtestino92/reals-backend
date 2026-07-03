package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.OffsetDateTime
import java.util.UUID

enum class UserReliabilityDimension {
    ResponsivenessScore,
    ResolutionQualityScore,
    SchedulingCommitmentScore,
    ConversationParticipationScore
}

enum class UserReliabilityEventType(
    val dimension: UserReliabilityDimension,
    val delta: Int
) {
    FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION(UserReliabilityDimension.ResolutionQualityScore, 2),
    FIRST_CHAT_MUTUAL_NO_SPARK_CLOSURE(UserReliabilityDimension.ResolutionQualityScore, 2),
    FIRST_CHAT_UNILATERAL_CLOSE_AFTER_MINIMUM_PARTICIPATION(UserReliabilityDimension.ResolutionQualityScore, -1),
    FIRST_CHAT_EARLY_UNILATERAL_CLOSE(UserReliabilityDimension.ResolutionQualityScore, -2),
    FIRST_CHAT_CLOSED_AFTER_COUNTERPARTY_INACTIVE(UserReliabilityDimension.ResponsivenessScore, -2),
    FIRST_CHAT_MUTUAL_CLOSE_REQUEST_IGNORED(UserReliabilityDimension.ResponsivenessScore, -2),
    FIRST_CHAT_EXPIRED_NO_DECISION(UserReliabilityDimension.ResponsivenessScore, -3),
    VISUAL_REVIEW_EXPIRED_NO_DECISION(UserReliabilityDimension.ResponsivenessScore, -2),
    SCHEDULING_SLOTS_PROPOSED_ON_TIME(UserReliabilityDimension.SchedulingCommitmentScore, 1),
    SCHEDULING_EXPIRED_NO_PROPOSAL(UserReliabilityDimension.SchedulingCommitmentScore, -3),
    SECOND_CHAT_CONFIRMED_ATTENDED(UserReliabilityDimension.SchedulingCommitmentScore, 4),
    SECOND_CHAT_NO_SHOW(UserReliabilityDimension.SchedulingCommitmentScore, -10),
    SAFETY_REPORT_DETERMINED_ABUSIVE(UserReliabilityDimension.ResolutionQualityScore, -8)
}

@Entity
@Table(
    name = "user_reliability_events",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_user_reliability_event_match",
            columnNames = ["user_id", "event_type", "related_match_id"]
        ),
        UniqueConstraint(
            name = "uq_user_reliability_event_connection",
            columnNames = ["user_id", "event_type", "related_connection_id"]
        ),
        UniqueConstraint(
            name = "uq_user_reliability_event_chat",
            columnNames = ["user_id", "event_type", "related_chat_id"]
        ),
        UniqueConstraint(
            name = "uq_user_reliability_event_safety_report",
            columnNames = ["user_id", "event_type", "related_safety_report_id"]
        )
    ]
)
data class UserReliabilityEvent(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "related_match_id")
    var relatedMatchId: UUID? = null,

    @Column(name = "related_connection_id")
    var relatedConnectionId: UUID? = null,

    @Column(name = "related_chat_id")
    var relatedChatId: UUID? = null,

    @Column(name = "related_safety_report_id")
    var relatedSafetyReportId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    var eventType: UserReliabilityEventType,

    @Enumerated(EnumType.STRING)
    @Column(name = "dimension", nullable = false)
    var dimension: UserReliabilityDimension,

    @Column(name = "delta", nullable = false)
    var delta: Int,

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "expires_at", nullable = false)
    var expiresAt: OffsetDateTime
)
