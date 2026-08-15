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
    val conversationStartedAt: OffsetDateTime?,
    val timeoutAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val endedAt: OffsetDateTime?,
    val readOnlyUntil: OffsetDateTime?,
    val lastMessageAt: OffsetDateTime?,
    val lastMessageSenderId: UUID?,
    val inactivityExpiresAt: OffsetDateTime?,
    val audioPolicy: ChatAudioPolicyResponse? = null
) {
    companion object {
        fun from(
            c: Chat,
            inactivityExpiresAt: OffsetDateTime? = null,
            audioPolicy: ChatAudioPolicyResponse? = null
        ) = ChatResponse(
            id = c.id,
            matchId = c.matchId,
            connectionId = c.connectionId,
            chatType = c.chatType,
            status = c.status,
            startedAt = c.startedAt,
            availableAt = c.availableAt,
            activatedAt = c.activatedAt,
            conversationStartedAt = c.conversationStartedAt,
            timeoutAt = c.timeoutAt,
            expiresAt = c.timeoutAt,
            endedAt = c.endedAt,
            readOnlyUntil = c.readOnlyUntil,
            lastMessageAt = c.lastMessageAt,
            lastMessageSenderId = c.lastMessageSenderId,
            inactivityExpiresAt = inactivityExpiresAt,
            audioPolicy = audioPolicy
        )
    }
}

data class ChatAudioPolicyResponse(
    val enabled: Boolean,
    val unavailableReason: com.reals.backend.service.ChatAudioUnavailableReason?,
    val enabledAt: OffsetDateTime?,
    val maxDurationMillis: Long,
    val maxFileSizeBytes: Long,
    val remainingMessages: Int?
) {
    companion object {
        fun from(policy: com.reals.backend.service.ChatAudioPolicy) =
            ChatAudioPolicyResponse(
                enabled = policy.enabled,
                unavailableReason = policy.unavailableReason,
                enabledAt = policy.enabledAt,
                maxDurationMillis = policy.maxDurationMillis,
                maxFileSizeBytes = policy.maxFileSizeBytes,
                remainingMessages = policy.remainingMessages
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
    val conversationStartedAt: OffsetDateTime?,
    val timeoutAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val endedAt: OffsetDateTime?,
    val readOnlyUntil: OffsetDateTime?,
    val lastMessageAt: OffsetDateTime?,
    val lastMessageSenderId: UUID?,
    val inactivityExpiresAt: OffsetDateTime?,
    val partner: PartnerSummaryResponse,
    val myDecision: ChatParticipantDecisionStatus,
    val partnerDecision: ChatParticipantDecisionStatus,
    val guidance: FirstChatGuidanceResponse?,
    val serverTime: OffsetDateTime,
    val audioPolicy: ChatAudioPolicyResponse? = null
) {
    companion object {
        fun from(
            chat: Chat,
            partner: Profile,
            myDecision: ChatParticipantDecisionStatus,
            partnerDecision: ChatParticipantDecisionStatus,
            inactivityExpiresAt: OffsetDateTime?,
            serverTime: OffsetDateTime,
            guidance: FirstChatGuidanceResponse? = null,
            audioPolicy: ChatAudioPolicyResponse? = null
        ) = FirstChatResponse(
            id = chat.id,
            matchId = chat.matchId,
            connectionId = chat.connectionId,
            chatType = chat.chatType,
            status = chat.status,
            startedAt = chat.startedAt,
            availableAt = chat.availableAt,
            activatedAt = chat.activatedAt,
            conversationStartedAt = chat.conversationStartedAt,
            timeoutAt = chat.timeoutAt,
            expiresAt = chat.timeoutAt,
            endedAt = chat.endedAt,
            readOnlyUntil = chat.readOnlyUntil,
            lastMessageAt = chat.lastMessageAt,
            lastMessageSenderId = chat.lastMessageSenderId,
            inactivityExpiresAt = inactivityExpiresAt,
            partner = PartnerSummaryResponse.from(partner),
            myDecision = myDecision,
            partnerDecision = partnerDecision,
            guidance = guidance,
            serverTime = serverTime,
            audioPolicy = audioPolicy
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
    val clientMessageId: UUID?,
    val messageType: ChatMessageType,
    val content: String?,
    val audio: ChatMessageAudioResponse?,
    val reactionType: ChatMessageReactionType?,
    val sentAt: OffsetDateTime
) {
    companion object {
        fun from(
            m: ChatMessage,
            audioUrlResolver: (ChatMessage) -> String = {
                error("Audio URL resolver is required for audio messages")
            }
        ) = ChatMessageResponse(
            id = m.id,
            chatSessionId = m.chatSessionId,
            senderId = m.senderId,
            clientMessageId = m.clientMessageId,
            messageType = m.messageType,
            content = m.content,
            audio = if (m.messageType == ChatMessageType.AUDIO) {
                ChatMessageAudioResponse(
                    url = audioUrlResolver(m),
                    durationMillis = requireNotNull(m.audioDurationMillis),
                    contentType = requireNotNull(m.audioContentType),
                    sizeBytes = requireNotNull(m.audioSizeBytes)
                )
            } else {
                null
            },
            reactionType = m.reactionType,
            sentAt = m.sentAt
        )
    }
}

data class PutMessageReactionRequest(
    val type: ChatMessageReactionType
)

data class ChatMessageAudioResponse(
    val url: String,
    val durationMillis: Long,
    val contentType: String,
    val sizeBytes: Long
)

data class ChatMessagesResponse(
    val messages: List<ChatMessageResponse>,
    val hasMore: Boolean,
    val serverTime: OffsetDateTime
) {
    companion object {
        fun from(
            messages: List<ChatMessage>,
            hasMore: Boolean = false,
            serverTime: OffsetDateTime = OffsetDateTime.now(),
            audioUrlResolver: (ChatMessage) -> String = {
                error("Audio URL resolver is required for audio messages")
            }
        ) = ChatMessagesResponse(
            messages = messages.map { ChatMessageResponse.from(it, audioUrlResolver) },
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

data class SecondChatResolutionRequestResponse(
    val id: UUID,
    val type: SecondChatResolutionRequestType,
    val requesterUserId: UUID,
    val responderUserId: UUID,
    val referenceMessageId: UUID?,
    val status: SecondChatResolutionRequestStatus,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime
) {
    companion object {
        fun from(r: SecondChatResolutionRequest) =
            SecondChatResolutionRequestResponse(
                id = r.id,
                type = r.type,
                requesterUserId = r.requesterUserId,
                responderUserId = r.responderUserId,
                referenceMessageId = r.referenceMessageId,
                status = r.status,
                createdAt = r.createdAt,
                expiresAt = r.expiresAt
            )
    }
}

data class SecondChatAttendanceResponse(
    val connectionId: UUID,
    val chatId: UUID?,
    val scheduledAt: OffsetDateTime,
    val onTimeUntil: OffsetDateTime,
    val entryClosesAt: OffsetDateTime,
    val absoluteExpiresAt: OffsetDateTime,
    val conversationStartedAt: OffsetDateTime?,
    val serverTime: OffsetDateTime,
    val myAttendanceStatus: SecondChatAttendanceStatus,
    val myJoinedAt: OffsetDateTime?,
    val partnerAttendanceStatus: SecondChatAttendanceStatus,
    val partnerJoinedAt: OffsetDateTime?,
    val canJoin: Boolean,
    val canClaimPartnerNoShow: Boolean,
    val activeNoShowClaim: SecondChatResolutionRequestResponse?,
    val activeResolutionRequest: SecondChatResolutionRequestResponse?,
    val chatStatus: ChatStatus?,
    val endedReason: ChatEndReason?,
    val endedAt: OffsetDateTime?,
    val readOnlyUntil: OffsetDateTime?,
    val mutualCompletionEligibleAt: OffsetDateTime?,
    val canRequestMutualCompletion: Boolean,
    val mutualCompletionCooldownUntil: OffsetDateTime?,
    val inactivityClaimableAt: OffsetDateTime?,
    val inactivityClosesAt: OffsetDateTime?,
    val canClaimPartnerInactivity: Boolean,
    val mustRespondToPartner: Boolean,
    val lastMessageAt: OffsetDateTime?,
    val lastMessageSenderId: UUID?,
    val audioPolicy: ChatAudioPolicyResponse? = null
) {
    companion object {
        fun from(
            view: com.reals.backend.service.SecondChatLifecycleService.SecondChatAttendanceView,
            audioPolicy: ChatAudioPolicyResponse? = null
        ) =
            SecondChatAttendanceResponse(
                connectionId = view.connectionId,
                chatId = view.chatId,
                scheduledAt = view.scheduledAt,
                onTimeUntil = view.onTimeUntil,
                entryClosesAt = view.entryClosesAt,
                absoluteExpiresAt = view.absoluteExpiresAt,
                conversationStartedAt = view.conversationStartedAt,
                serverTime = view.serverTime,
                myAttendanceStatus = view.myAttendanceStatus,
                myJoinedAt = view.myJoinedAt,
                partnerAttendanceStatus = view.partnerAttendanceStatus,
                partnerJoinedAt = view.partnerJoinedAt,
                canJoin = view.canJoin,
                canClaimPartnerNoShow = view.canClaimPartnerNoShow,
                activeNoShowClaim = view.activeNoShowClaim?.let { SecondChatResolutionRequestResponse.from(it) },
                activeResolutionRequest =
                    view.conversation.activeResolutionRequest?.let { SecondChatResolutionRequestResponse.from(it) },
                chatStatus = view.conversation.chatStatus,
                endedReason = view.conversation.endedReason,
                endedAt = view.conversation.endedAt,
                readOnlyUntil = view.conversation.readOnlyUntil,
                mutualCompletionEligibleAt = view.conversation.mutualCompletionEligibleAt,
                canRequestMutualCompletion = view.conversation.canRequestMutualCompletion,
                mutualCompletionCooldownUntil = view.conversation.mutualCompletionCooldownUntil,
                inactivityClaimableAt = view.conversation.inactivityClaimableAt,
                inactivityClosesAt = view.conversation.inactivityClosesAt,
                canClaimPartnerInactivity = view.conversation.canClaimPartnerInactivity,
                mustRespondToPartner = view.conversation.mustRespondToPartner,
                lastMessageAt = view.conversation.lastMessageAt,
                lastMessageSenderId = view.conversation.lastMessageSenderId,
                audioPolicy = audioPolicy
            )
    }
}

data class SecondChatCompletionDecisionRequest(
    val decision: com.reals.backend.service.SecondChatConversationLifecycleService.CompletionDecision
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
