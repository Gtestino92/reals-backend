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
    val id: UUID = UUID.randomUUID(),

    @Column(name = "chat_session_id", nullable = false)
    val chatSessionId: UUID,

    @Column(name = "sender_id", nullable = false)
    val senderId: UUID,

    @Column(name = "content", nullable = false)
    val content: String,

    @Column(name = "sent_at", nullable = false)
    val sentAt: OffsetDateTime = OffsetDateTime.now()
)
