package com.reals.backend.controller.dto

import com.reals.backend.domain.*
import com.reals.backend.service.FirstChatGuidanceState
import com.reals.backend.validation.PlainText
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
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
    val expiresAt: OffsetDateTime,
    val endedAt: OffsetDateTime?,
    val readOnlyUntil: OffsetDateTime?,
    val lastMessageAt: OffsetDateTime?,
    val inactivityExpiresAt: OffsetDateTime?
) {
    companion object {
        fun from(
            c: Chat,
            inactivityExpiresAt: OffsetDateTime? = null
        ) = ChatResponse(
            id = c.id,
            matchId = c.matchId,
            connectionId = c.connectionId,
            chatType = c.chatType,
            status = c.status,
            startedAt = c.startedAt,
            availableAt = c.availableAt,
            activatedAt = c.activatedAt,
            timeoutAt = c.timeoutAt,
            expiresAt = c.timeoutAt,
            endedAt = c.endedAt,
            readOnlyUntil = c.readOnlyUntil,
            lastMessageAt = c.lastMessageAt,
            inactivityExpiresAt = inactivityExpiresAt
        )
    }
}

data class PartnerSummaryResponse(
    val userId: UUID,
    val profileId: UUID,
    val displayName: String
) {
    companion object {
        fun from(profile: Profile) = PartnerSummaryResponse(
            userId = profile.userId,
            profileId = profile.id,
            displayName = profile.displayName
        )
    }
}

data class FirstChatResponse(
    val id: UUID,
    val matchId: UUID,
    val connectionId: UUID?,
    val chatType: ChatType,
    val status: ChatStatus,
    val startedAt: OffsetDateTime,
    val availableAt: OffsetDateTime?,
    val activatedAt: OffsetDateTime?,
    val timeoutAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val endedAt: OffsetDateTime?,
    val readOnlyUntil: OffsetDateTime?,
    val lastMessageAt: OffsetDateTime?,
    val inactivityExpiresAt: OffsetDateTime?,
    val partner: PartnerSummaryResponse,
    val myDecision: ChatParticipantDecisionStatus,
    val partnerDecision: ChatParticipantDecisionStatus,
    val guidance: FirstChatGuidanceResponse?
) {
    companion object {
        fun from(
            chat: Chat,
            partner: Profile,
            myDecision: ChatParticipantDecisionStatus,
            partnerDecision: ChatParticipantDecisionStatus,
            inactivityExpiresAt: OffsetDateTime?,
            guidance: FirstChatGuidanceResponse? = null
        ) = FirstChatResponse(
            id = chat.id,
            matchId = chat.matchId,
            connectionId = chat.connectionId,
            chatType = chat.chatType,
            status = chat.status,
            startedAt = chat.startedAt,
            availableAt = chat.availableAt,
            activatedAt = chat.activatedAt,
            timeoutAt = chat.timeoutAt,
            expiresAt = chat.timeoutAt,
            endedAt = chat.endedAt,
            readOnlyUntil = chat.readOnlyUntil,
            lastMessageAt = chat.lastMessageAt,
            inactivityExpiresAt = inactivityExpiresAt,
            partner = PartnerSummaryResponse.from(partner),
            myDecision = myDecision,
            partnerDecision = partnerDecision,
            guidance = guidance
        )
    }
}

data class FirstChatGuidanceQuestionResponse(
    val id: String,
    val text: String
)

data class FirstChatGuidanceResponse(
    val question: FirstChatGuidanceQuestionResponse,
    val questionOrdinal: Int,
    val maxQuestions: Int,
    val requiredCharacters: Int,
    val canRequestNext: Boolean,
    val myNextRequested: Boolean,
    val completed: Boolean
) {
    companion object {
        fun from(state: FirstChatGuidanceState) =
            FirstChatGuidanceResponse(
                question = FirstChatGuidanceQuestionResponse(
                    id = state.questionId,
                    text = state.questionText
                ),
                questionOrdinal = state.questionOrdinal,
                maxQuestions = state.maxQuestions,
                requiredCharacters = state.requiredCharacters,
                canRequestNext = state.canRequestNext,
                myNextRequested = state.myNextRequested,
                completed = state.completed
            )
    }
}

// — Messages

data class SendMessageRequest(
    @field:NotBlank
    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val content: String
)

data class ChatExitRequestCreateRequest(
    val reason: ChatExitReason? = ChatExitReason.NO_LONGER_INTERESTED,

    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val details: String? = null
)

data class ChatCancellationRequest(
    val reason: ChatExitReason? = ChatExitReason.NO_LONGER_INTERESTED,

    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val details: String? = null
)

data class ChatSafetyCancellationRequest(
    val reason: ChatExitReason = ChatExitReason.INAPPROPRIATE_BEHAVIOR,

    @field:NotBlank
    @field:Size(max = 1000)
    @field:Pattern(regexp = PlainText.REGEX, message = PlainText.MESSAGE)
    val details: String
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

data class ChatMessagesResponse(
    val messages: List<ChatMessageResponse>,
    val hasMore: Boolean,
    val serverTime: OffsetDateTime
) {
    companion object {
        fun from(
            messages: List<ChatMessage>,
            hasMore: Boolean = false,
            serverTime: OffsetDateTime = OffsetDateTime.now()
        ) = ChatMessagesResponse(
            messages = messages.map { ChatMessageResponse.from(it) },
            hasMore = hasMore,
            serverTime = serverTime
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
        fun from(
            o: ChatExitOutcome,
            inactivityExpiresAt: OffsetDateTime? = null
        ) =
            ChatExitOutcomeResponse(
                chat = ChatResponse.from(
                    c = o.chat,
                    inactivityExpiresAt = inactivityExpiresAt
                ),
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
    val schedulingAvailableAt: OffsetDateTime?,
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
            schedulingAvailableAt = c.schedulingAvailableAt,
            schedulingExpiresAt = c.schedulingExpiresAt,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt
        )
    }
}

// — VisualReview personal messages

data class ConnectionDismissalResponse(
    val dismissed: Boolean
)

/**
 * The personal message the partner left for the requesting user.
 * null if the partner hasn't submitted one yet.
 */
data class PartnerMessageResponse(
    val message: String?
)

// — Scheduling

data class AddProposalRequest(
    @field:Positive
    val expectedRoundNumber: Int,

    @field:NotEmpty
    val proposedDateTimes: List<OffsetDateTime>
)

data class RejectPartnerProposalsRequest(
    @field:Positive
    val expectedRoundNumber: Int
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
    val schedulingExpiresAt: OffsetDateTime,
    val chatId: UUID?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
) {
    companion object {
        fun from(
            n: ScheduleNegotiation,
            schedulingExpiresAt: OffsetDateTime,
            chatId: UUID? = null
        ) = NegotiationResponse(
            id = n.id,
            connectionId = n.connectionId,
            roundNumber = n.roundNumber,
            status = n.status,
            confirmedDateTime = n.confirmedDateTime,
            schedulingExpiresAt = schedulingExpiresAt,
            chatId = chatId,
            createdAt = n.createdAt,
            updatedAt = n.updatedAt
        )
    }
}
