package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(
    name = "first_chat_guidance",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_first_chat_guidance_chat",
            columnNames = ["chat_id"]
        )
    ]
)
data class FirstChatGuidance(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "chat_id", nullable = false)
    var chatId: UUID,

    @Column(name = "current_question_id", nullable = false, length = 64)
    var currentQuestionId: String,

    @Column(name = "current_question_text", nullable = false)
    var currentQuestionText: String,

    @Column(name = "current_question_ordinal", nullable = false)
    var currentQuestionOrdinal: Int,

    @Column(name = "current_question_activated_at", nullable = false)
    var currentQuestionActivatedAt: OffsetDateTime,

    @Column(name = "user_a_next_requested_at")
    var userANextRequestedAt: OffsetDateTime? = null,

    @Column(name = "user_b_next_requested_at")
    var userBNextRequestedAt: OffsetDateTime? = null,

    @Column(name = "completed_at")
    var completedAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
