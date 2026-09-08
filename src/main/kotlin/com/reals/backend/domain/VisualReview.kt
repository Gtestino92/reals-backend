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
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "match_id", nullable = false)
    var matchId: UUID,

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

    @Column(name = "personal_message_a_read_by_b_at")
    var personalMessageAReadByBAt: OffsetDateTime? = null,

    @Column(name = "personal_message_b_read_by_a_at")
    var personalMessageBReadByAAt: OffsetDateTime? = null,

    @Column(name = "messages_visible", nullable = false)
    var messagesVisible: Boolean = false,

    @Column(name = "expires_at")
    var expiresAt: OffsetDateTime? = null,

    @Column(name = "available_at", nullable = false)
    var availableAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "reminder_eligible_at")
    var reminderEligibleAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    fun decisionFor(
        userId: UUID,
        userAId: UUID,
        userBId: UUID
    ): VisualDecision? =
        when (userId) {
            userAId -> userAVisualDecision
            userBId -> userBVisualDecision
            else -> throw IllegalArgumentException("User $userId does not belong to visual review $id")
        }

    fun hasPendingDecisionFor(
        userId: UUID,
        userAId: UUID,
        userBId: UUID
    ): Boolean =
        decisionFor(
            userId = userId,
            userAId = userAId,
            userBId = userBId
        ) == null

    fun recordDecisionFor(
        userId: UUID,
        userAId: UUID,
        userBId: UUID,
        decision: VisualDecision
    ) {
        when (userId) {
            userAId -> userAVisualDecision = decision
            userBId -> userBVisualDecision = decision
            else -> throw IllegalArgumentException("User $userId does not belong to visual review $id")
        }
    }

    fun bothDecided(): Boolean =
        userAVisualDecision != null && userBVisualDecision != null

    fun bothApproved(): Boolean =
        userAVisualDecision == VisualDecision.APPROVED &&
            userBVisualDecision == VisualDecision.APPROVED

    fun anyRejected(): Boolean =
        userAVisualDecision == VisualDecision.REJECTED ||
            userBVisualDecision == VisualDecision.REJECTED
}
