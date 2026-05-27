package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

enum class ChatExitRequestType {
    MUTUAL_CANCEL,
    UNILATERAL_CANCEL,
    SAFETY_REPORT
}

enum class ChatExitRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}

enum class ChatExitReason {
    NO_LONGER_INTERESTED,
    INAPPROPRIATE_BEHAVIOR,
    HARASSMENT,
    OTHER
}

@Entity
@Table(name = "chat_exit_requests")
data class ChatExitRequest(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "chat_id", nullable = false)
    val chatId: UUID,

    @Column(name = "requester_user_id", nullable = false)
    val requesterUserId: UUID,

    @Column(name = "responder_user_id", nullable = false)
    val responderUserId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false)
    val type: ChatExitRequestType,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ChatExitRequestStatus = ChatExitRequestStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(name = "reason")
    val reason: ChatExitReason? = null,

    @Column(name = "details")
    val details: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "resolved_at")
    var resolvedAt: OffsetDateTime? = null
)
