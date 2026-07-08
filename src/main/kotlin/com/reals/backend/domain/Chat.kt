package com.reals.backend.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

enum class ChatType {
    FIRST_CHAT,
    SECOND_CHAT
}

enum class ChatStatus {
    AVAILABLE,
    ACTIVE,
    CANCELLED,
    EXPIRED,
    ABANDONED,
    CLOSED,
    FINISHED
}

enum class ChatEndReason {
    MUTUAL_CANCEL,
    UNILATERAL_CANCEL,
    SAFETY_REPORT,
    USER_BLOCK,
    ABSOLUTE_TIMEOUT,
    INACTIVITY_TIMEOUT,
    SECOND_CHAT_READ_ONLY_EXPIRED,
    USER_DELETED,
    SYSTEM_CLOSED
}

@Entity
@Table(
    name = "chats",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_chat_match_type",
            columnNames = ["match_id", "chat_type"]
        ),
        UniqueConstraint(
            name = "uq_chat_connection_type",
            columnNames = ["connection_id", "chat_type"]
        )
    ]
)
data class Chat(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "match_id", nullable = false)
    var matchId: UUID,

    @Column(name = "connection_id")
    var connectionId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "chat_type", nullable = false)
    var chatType: ChatType,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ChatStatus = ChatStatus.ACTIVE,

    @Column(name = "started_at", nullable = false)
    var startedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "available_at")
    var availableAt: OffsetDateTime? = null,

    @Column(name = "activated_at")
    var activatedAt: OffsetDateTime? = null,

    @Column(name = "timeout_at", nullable = false)
    var timeoutAt: OffsetDateTime,

    @Column(name = "ended_at")
    var endedAt: OffsetDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "ended_reason")
    var endedReason: ChatEndReason? = null,

    @Column(name = "read_only_until")
    var readOnlyUntil: OffsetDateTime? = null,

    @Column(name = "last_message_at")
    var lastMessageAt: OffsetDateTime? = null
)
