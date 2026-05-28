package com.reals.backend.controller.dto

import com.reals.backend.domain.*
import java.time.OffsetDateTime
import java.util.*

// — Chat

data class ChatResponse(
    val id: UUID,
    val matchId: UUID,
    val connectionId: UUID?,
    val chatType: ChatType,
    val status: ChatStatus,
    val startedAt: OffsetDateTime,
    val availableAt: OffsetDateTime?,
    val activatedAt: OffsetDateTime?,
    val timeoutAt: OffsetDateTime,
    val endedAt: OffsetDateTime?,
    val lastMessageAt: OffsetDateTime?
) {
    companion object {
        fun from(c: Chat) = ChatResponse(
            id = c.id,
            matchId = c.matchId,
            connectionId = c.connectionId,
            chatType = c.chatType,
            status = c.status,
            startedAt = c.startedAt,
            availableAt = c.availableAt,
            activatedAt = c.activatedAt,
            timeoutAt = c.timeoutAt,
            endedAt = c.endedAt,
            lastMessageAt = c.lastMessageAt
        )
    }
}

// — Messages

data class SendMessageRequest(
    val content: String
)

data class ChatExitRequestCreateRequest(
    val reason: ChatExitReason? = ChatExitReason.NO_LONGER_INTERESTED,
    val details: String? = null
)

data class ChatCancellationRequest(
    val reason: ChatExitReason? = ChatExitReason.NO_LONGER_INTERESTED,
    val details: String? = null
)

data class ChatSafetyCancellationRequest(
    val reason: ChatExitReason = ChatExitReason.INAPPROPRIATE_BEHAVIOR,
    val details: String? = null
)

data class ChatMessageResponse(
    val id: UUID,
    val chatSessionId: UUID,
    val senderId: UUID,
    val content: String,
    val sentAt: OffsetDateTime
) {
    companion object {
        fun from(m: ChatMessage) = ChatMessageResponse(
            id = m.id,
            chatSessionId = m.chatSessionId,
            senderId = m.senderId,
            content = m.content,
            sentAt = m.sentAt
        )
    }
}

// — Connection

data class ChatExitRequestResponse(
    val id: UUID,
    val chatId: UUID,
    val requesterUserId: UUID,
    val responderUserId: UUID,
    val type: ChatExitRequestType,
    val status: ChatExitRequestStatus,
    val reason: ChatExitReason?,
    val details: String?,
    val createdAt: OffsetDateTime,
    val resolvedAt: OffsetDateTime?
) {
    companion object {
        fun from(r: ChatExitRequest) = ChatExitRequestResponse(
            id = r.id,
            chatId = r.chatId,
            requesterUserId = r.requesterUserId,
            responderUserId = r.responderUserId,
            type = r.type,
            status = r.status,
            reason = r.reason,
            details = r.details,
            createdAt = r.createdAt,
            resolvedAt = r.resolvedAt
        )
    }
}

data class ChatExitOutcomeResponse(
    val chat: ChatResponse,
    val exitRequest: ChatExitRequestResponse,
    val penaltyApplied: Boolean,
    val penalizedUserId: UUID?
) {
    companion object {
        fun from(o: com.reals.backend.service.ChatExitOutcome) =
            ChatExitOutcomeResponse(
                chat = ChatResponse.from(o.chat),
                exitRequest = ChatExitRequestResponse.from(o.exitRequest),
                penaltyApplied = o.penaltyApplied,
                penalizedUserId = o.penalizedUserId
            )
    }
}

data class ConnectionResponse(
    val id: UUID,
    val matchId: UUID,
    val userAId: UUID,
    val userBId: UUID,
    val state: ConnectionState,
    val schedulingExpiresAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
) {
    companion object {
        fun from(c: Connection) = ConnectionResponse(
            id = c.id,
            matchId = c.matchId,
            userAId = c.userAId,
            userBId = c.userBId,
            state = c.state,
            schedulingExpiresAt = c.schedulingExpiresAt,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt
        )
    }
}

// — VisualReview personal messages

/**
 * The personal message the partner left for the requesting user.
 * null if the partner hasn't submitted one yet.
 *
 * TODO(front): mostrar en la pantalla de negociación de horario.
 * TODO(product): ver PENDING.md #17 - decidir si exponer message solo cuando
 * ambos lo hayan enviado, o desde que entra en SCHEDULING_PHASE.
 */
data class PartnerMessageResponse(
    val message: String?
)

// — Scheduling

data class AddProposalRequest(
    val proposedDateTimes: List<OffsetDateTime>
)

data class ScheduleProposalResponse(
    val id: UUID,
    val connectionId: UUID,
    val userId: UUID,
    val roundNumber: Int,
    val preferenceOrder: Int,
    val proposedDateTime: OffsetDateTime,
    val status: ProposalStatus,
    val chatId: UUID?,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun from(
            p: ScheduleProposal,
            chatId: UUID? = null
        ) = ScheduleProposalResponse(
            id = p.id,
            connectionId = p.connectionId,
            userId = p.userId,
            roundNumber = p.roundNumber,
            preferenceOrder = p.preferenceOrder,
            proposedDateTime = p.proposedDateTime,
            status = p.status,
            chatId = chatId,
            createdAt = p.createdAt
        )
    }
}

data class NegotiationResponse(
    val id: UUID,
    val connectionId: UUID,
    val roundNumber: Int,
    val status: NegotiationStatus,
    val confirmedDateTime: OffsetDateTime?,
    val chatId: UUID?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
) {
    companion object {
        fun from(
            n: ScheduleNegotiation,
            chatId: UUID? = null
        ) = NegotiationResponse(
            id = n.id,
            connectionId = n.connectionId,
            roundNumber = n.roundNumber,
            status = n.status,
            confirmedDateTime = n.confirmedDateTime,
            chatId = chatId,
            createdAt = n.createdAt,
            updatedAt = n.updatedAt
        )
    }
}
