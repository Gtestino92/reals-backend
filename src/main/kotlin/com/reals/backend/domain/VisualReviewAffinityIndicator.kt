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
    name = "visual_review_affinity_indicators",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_visual_review_affinity_indicators_match_ordinal",
            columnNames = ["match_id", "ordinal"]
        ),
        UniqueConstraint(
            name = "uq_visual_review_affinity_indicators_match_category",
            columnNames = ["match_id", "category_id"]
        )
    ],
    indexes = [
        Index(
            name = "idx_visual_review_affinity_indicators_match",
            columnList = "match_id"
        )
    ]
)
data class VisualReviewAffinityIndicator(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "match_id", nullable = false)
    var matchId: UUID,

    @Column(name = "ordinal", nullable = false)
    var ordinal: Int,

    @Column(name = "category_id", nullable = false, length = 96)
    var categoryId: String,

    @Column(name = "category_title", nullable = false)
    var categoryTitle: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)
