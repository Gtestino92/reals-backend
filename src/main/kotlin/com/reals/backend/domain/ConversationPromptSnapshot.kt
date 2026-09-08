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

enum class ConversationPromptSnapshotSourceType {
    AFFINITY,
    GENERIC
}

enum class ConversationPromptSnapshotKind {
    SHARED_AFFINITY,
    CONSTRUCTIVE_CONTRAST
}

@Entity
@Table(
    name = "conversation_prompt_snapshots",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_conversation_prompt_snapshots_chat_ordinal",
            columnNames = ["chat_id", "ordinal"]
        ),
        UniqueConstraint(
            name = "uq_conversation_prompt_snapshots_chat_source",
            columnNames = ["chat_id", "source_type", "source_question_id"]
        )
    ],
    indexes = [
        Index(
            name = "idx_conversation_prompt_snapshots_chat",
            columnList = "chat_id"
        )
    ]
)
data class ConversationPromptSnapshot(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "chat_id", nullable = false)
    var chatId: UUID,

    @Column(name = "ordinal", nullable = false)
    var ordinal: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    var sourceType: ConversationPromptSnapshotSourceType,

    @Column(name = "source_question_id", nullable = false, length = 96)
    var sourceQuestionId: String,

    @Column(name = "source_question_semantic_version")
    var sourceQuestionSemanticVersion: Int? = null,

    @Column(name = "prompt_text", nullable = false)
    var promptText: String,

    @Column(name = "category_id", length = 96)
    var categoryId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "conversation_kind", length = 32)
    var conversationKind: ConversationPromptSnapshotKind? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)
