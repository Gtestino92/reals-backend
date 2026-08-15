package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.*

enum class ChatMessageType {
    TEXT,
    AUDIO
}

enum class ChatMessageReactionType {
    HEART
}

@Entity
@Table(name = "chat_messages")
data class ChatMessage(

    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "chat_session_id", nullable = false)
    var chatSessionId: UUID,

    @Column(name = "sender_id", nullable = false)
    var senderId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    var messageType: ChatMessageType = ChatMessageType.TEXT,

    @Column(name = "client_message_id")
    var clientMessageId: UUID? = null,

    @Column(name = "content")
    var content: String? = null,

    @Column(name = "audio_bucket")
    var audioBucket: String? = null,

    @Column(name = "audio_object_key")
    var audioObjectKey: String? = null,

    @Column(name = "audio_content_type")
    var audioContentType: String? = null,

    @Column(name = "audio_size_bytes")
    var audioSizeBytes: Long? = null,

    @Column(name = "audio_duration_millis")
    var audioDurationMillis: Long? = null,

    @Column(name = "audio_sha256")
    var audioSha256: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type")
    var reactionType: ChatMessageReactionType? = null,

    @Column(name = "sent_at", nullable = false)
    var sentAt: OffsetDateTime = OffsetDateTime.now()
)
