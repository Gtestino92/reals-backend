package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(
    name = "safety_report_evidence_snapshots",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_safety_report_evidence_report",
            columnNames = ["safety_report_id"]
        )
    ],
    indexes = [
        Index(name = "idx_safety_report_evidence_chat_id", columnList = "chat_id"),
        Index(name = "idx_safety_report_evidence_match_id", columnList = "match_id"),
        Index(name = "idx_safety_report_evidence_connection_id", columnList = "connection_id")
    ]
)
data class SafetyReportEvidenceSnapshot(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "safety_report_id", nullable = false)
    var safetyReportId: UUID,

    @Column(name = "chat_id")
    var chatId: UUID? = null,

    @Column(name = "match_id")
    var matchId: UUID? = null,

    @Column(name = "connection_id")
    var connectionId: UUID? = null,

    @Column(name = "message_count", nullable = false)
    var messageCount: Int = 0,

    @Column(name = "first_message_at")
    var firstMessageAt: OffsetDateTime? = null,

    @Column(name = "last_message_at")
    var lastMessageAt: OffsetDateTime? = null,

    @Column(name = "transcript_sha256")
    var transcriptSha256: String? = null,

    @Column(name = "captured_at", nullable = false)
    var capturedAt: OffsetDateTime = OffsetDateTime.now()
)
