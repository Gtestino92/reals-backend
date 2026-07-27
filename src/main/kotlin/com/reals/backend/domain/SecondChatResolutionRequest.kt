package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.OffsetDateTime
import java.util.UUID

enum class SecondChatResolutionRequestType {
    PARTNER_NO_SHOW,
    MUTUAL_COMPLETION,
    PARTNER_INACTIVITY
}

enum class SecondChatResolutionRequestStatus {
    PENDING,
    CANCELLED,
    COMPLETED,
    ACCEPTED,
    REJECTED,
    TIMED_OUT
}

@Entity
@Table(
    name = "second_chat_resolution_requests",
    indexes = [
        Index(name = "idx_second_chat_resolution_connection", columnList = "connection_id"),
        Index(name = "idx_second_chat_resolution_chat", columnList = "chat_id"),
        Index(name = "idx_second_chat_resolution_pending_expiry", columnList = "status,type,expires_at")
    ]
)
data class SecondChatResolutionRequest(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "connection_id", nullable = false)
    var connectionId: UUID,

    @Column(name = "chat_id")
    var chatId: UUID? = null,

    @Column(name = "reference_message_id")
    var referenceMessageId: UUID? = null,

    @Column(name = "requester_user_id", nullable = false)
    var requesterUserId: UUID,

    @Column(name = "responder_user_id", nullable = false)
    var responderUserId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    var type: SecondChatResolutionRequestType,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: SecondChatResolutionRequestStatus = SecondChatResolutionRequestStatus.PENDING,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: OffsetDateTime,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "resolved_at")
    var resolvedAt: OffsetDateTime? = null
)
