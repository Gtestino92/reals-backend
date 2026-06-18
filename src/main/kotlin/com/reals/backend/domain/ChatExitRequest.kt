package com.reals.backend.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
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
    REJECTED,
    TIMED_OUT
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
    var id: UUID = UUID.randomUUID(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "chat_id", nullable = false)
    var chatId: UUID,

    @Column(name = "requester_user_id", nullable = false)
    var requesterUserId: UUID,

    @Column(name = "responder_user_id", nullable = false)
    var responderUserId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false)
    var type: ChatExitRequestType,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ChatExitRequestStatus = ChatExitRequestStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(name = "reason")
    var reason: ChatExitReason? = null,

    @Column(name = "details")
    var details: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "resolved_at")
    var resolvedAt: OffsetDateTime? = null
)

data class ChatExitRequestCreationResult(
    val exitRequest: ChatExitRequest,
    val created: Boolean
)
