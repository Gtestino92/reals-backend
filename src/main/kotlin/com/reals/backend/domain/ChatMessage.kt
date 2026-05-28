package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.*

@Entity
@Table(name = "chat_messages")
data class ChatMessage(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "chat_session_id", nullable = false)
    var chatSessionId: UUID,

    @Column(name = "sender_id", nullable = false)
    var senderId: UUID,

    @Column(name = "content", nullable = false)
    var content: String,

    @Column(name = "sent_at", nullable = false)
    var sentAt: OffsetDateTime = OffsetDateTime.now()
)
