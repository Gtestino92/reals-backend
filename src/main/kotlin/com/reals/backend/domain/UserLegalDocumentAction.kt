package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(
    name = "user_legal_document_actions",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_user_legal_document_action_user_type_version",
            columnNames = ["user_id", "document_type", "document_version"]
        )
    ],
    indexes = [
        Index(
            name = "idx_user_legal_document_actions_user_id",
            columnList = "user_id"
        ),
        Index(
            name = "idx_user_legal_document_actions_user_type",
            columnList = "user_id, document_type"
        )
    ]
)
data class UserLegalDocumentAction(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    var documentType: LegalDocumentType,

    @Column(name = "document_version", nullable = false)
    var documentVersion: String,

    @Column(name = "document_content_sha256", length = 64)
    var documentContentSha256: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    var action: LegalDocumentAction,

    @Column(name = "acted_at", nullable = false)
    var actedAt: OffsetDateTime = OffsetDateTime.now()
)
