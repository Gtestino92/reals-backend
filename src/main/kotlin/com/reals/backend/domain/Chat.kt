package com.reals.backend.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

enum class ChatType {
    FIRST_CHAT,
    SECOND_CHAT
}

enum class ChatStatus {
    ACTIVE,
    EXPIRED,
    ABANDONED,
    CLOSED,
    FINISHED
}

@Entity
@Table(name = "chats")
data class Chat(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "match_id", nullable = false)
    val matchId: UUID,

    @Column(name = "connection_id")
    val connectionId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "chat_type", nullable = false)
    val chatType: ChatType,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ChatStatus = ChatStatus.ACTIVE,

    @Column(name = "started_at", nullable = false)
    val startedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "timeout_at", nullable = false)
    val timeoutAt: OffsetDateTime,

    @Column(name = "ended_at")
    var endedAt: OffsetDateTime? = null,

    @Column(name = "last_message_at")
    var lastMessageAt: OffsetDateTime? = null
)
