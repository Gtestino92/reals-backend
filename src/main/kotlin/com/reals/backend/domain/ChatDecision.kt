package com.reals.backend.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Records the individual chat continuation decision for each user in a FIRST_CHAT.
 *
 * When both decisions are registered, the match transitions automatically:
 * - BOTH APPROVED -> CHAT_APPROVED -> VISUAL_PHASE
 * - ANY REJECTED -> CHAT_REJECTED, locks released
 */
@Entity
@Table(name = "chat_decisions")
class ChatDecision(

    @Id
    @Column(updatable = false)
    val id: UUID = UUID.randomUUID(),

    /**
     * The chat session this decision belongs to.
     */
    @Column(name = "chat_id", nullable = false)
    val chatId: UUID,

    /**
     * The match this decision belongs to.
     * Denormalized for easy lookup.
     */
    @Column(name = "match_id", nullable = false)
    val matchId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "user_a_decision")
    var userADecision: ChatContinueDecision? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "user_b_decision")
    var userBDecision: ChatContinueDecision? = null,

    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)

enum class ChatContinueDecision {
    APPROVED,
    REJECTED
}