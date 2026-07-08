package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

enum class AuditEventType {
    SAFETY_REPORT_CREATED,
    SAFETY_REPORT_DISMISSED,
    SAFETY_REPORT_CONFIRMED,
    USER_BLOCK_CREATED,
    CHAT_ENDED,
    PROFILE_PHOTO_UPLOADED,
    PROFILE_PHOTO_REPLACED,
    PROFILE_PHOTO_DELETED,
    PROFILE_PHOTOS_REORDERED,
    PROFILE_ACTIVATED,
    PHOTO_MODERATION_UPDATED,
    IDENTITY_VERIFICATION_UPDATED,
    ACCOUNT_DELETION_REQUESTED,
    ACCOUNT_REACTIVATED,
    PENALTY_APPLIED,
    LEGAL_DOCUMENT_ACTION_RECORDED
}

enum class AuditAggregateType {
    USER,
    PROFILE,
    PROFILE_PHOTO,
    CHAT,
    MATCH,
    CONNECTION,
    SAFETY_REPORT,
    USER_BLOCK,
    PENALTY
}

@Entity
@Table(
    name = "audit_events",
    indexes = [
        Index(name = "idx_audit_events_actor_user_id", columnList = "actor_user_id"),
        Index(name = "idx_audit_events_target_user_id", columnList = "target_user_id"),
        Index(name = "idx_audit_events_event_type", columnList = "event_type"),
        Index(name = "idx_audit_events_aggregate", columnList = "aggregate_type, aggregate_id"),
        Index(name = "idx_audit_events_created_at", columnList = "created_at")
    ]
)
data class AuditEvent(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    var eventType: AuditEventType,

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", nullable = false)
    var aggregateType: AuditAggregateType,

    @Column(name = "aggregate_id", nullable = false)
    var aggregateId: UUID,

    @Column(name = "actor_user_id")
    var actorUserId: UUID? = null,

    @Column(name = "target_user_id")
    var targetUserId: UUID? = null,

    @Column(name = "request_id")
    var requestId: String? = null,

    @Column(name = "ip_hash")
    var ipHash: String? = null,

    @Column(name = "user_agent_hash")
    var userAgentHash: String? = null,

    @Column(name = "metadata_json", columnDefinition = "text")
    var metadataJson: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)
