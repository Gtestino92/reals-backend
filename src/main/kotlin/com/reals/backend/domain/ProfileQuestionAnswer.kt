package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(
    name = "profile_question_answers",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_profile_question_answers_profile_question",
            columnNames = ["profile_id", "question_id"]
        )
    ],
    indexes = [
        Index(
            name = "idx_profile_question_answers_profile",
            columnList = "profile_id"
        ),
        Index(
            name = "idx_profile_question_answers_question",
            columnList = "question_id"
        ),
        Index(
            name = "idx_profile_question_answers_profile_selected",
            columnList = "profile_id, selected_position"
        )
    ]
)
data class ProfileQuestionAnswer(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "profile_id", nullable = false)
    var profileId: UUID,

    @Column(name = "question_id", nullable = false, length = 64)
    var questionId: String,

    @Column(name = "question_semantic_version", nullable = false)
    var questionSemanticVersion: Int,

    @Column(name = "answer_text", nullable = false, length = 160)
    var answerText: String,

    @Column(name = "selected_position")
    var selectedPosition: Int? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
