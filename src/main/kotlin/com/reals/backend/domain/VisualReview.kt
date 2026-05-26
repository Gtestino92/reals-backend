package com.reals.backend.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

enum class VisualDecision {
    APPROVED,
    REJECTED
}

@Entity
@Table(name = "visual_reviews")
data class VisualReview(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "match_id", nullable = false)
    val matchId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "user_a_visual_decision")
    var userAVisualDecision: VisualDecision? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "user_b_visual_decision")
    var userBVisualDecision: VisualDecision? = null,

    @Column(name = "personal_message_a")
    var personalMessageA: String? = null,

    @Column(name = "personal_message_b")
    var personalMessageB: String? = null,

    @Column(name = "messages_visible", nullable = false)
    var messagesVisible: Boolean = false,

    @Column(name = "expires_at")
    val expiresAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
