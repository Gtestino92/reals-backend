package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatExitRequest
import com.reals.backend.domain.ChatExitRequestStatus
import com.reals.backend.domain.ChatExitRequestType
import com.reals.backend.domain.ChatExitOutcome
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.repository.ChatExitRequestRepository
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ChatRepository
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.NoSuchElementException
import java.util.UUID

@Service
@Transactional
class ChatExitService(
    private val chatRepository: ChatRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val chatExitRequestRepository: ChatExitRequestRepository,
    private val matchService: MatchService,
    private val penaltyService: PenaltyService,
    private val connectionService: ConnectionService,

    @param:Value("\${chat.first-chat.min-messages-before-free-cancel:0}")
    private val firstChatMinMessagesBeforeFreeCancel: Int,

    @param:Value("\${chat.second-chat.min-messages-before-free-cancel:0}")
    private val secondChatMinMessagesBeforeFreeCancel: Int
) {

    fun requestMutualCancellation(
        chatId: UUID,
        requesterUserId: UUID,
        reason: ChatExitReason? = ChatExitReason.NO_LONGER_INTERESTED,
        details: String? = null
    ): ChatExitRequest {
        val chat = findChatOrThrow(chatId)
        validateActiveChatWindow(chat)
        val responderUserId = resolvePartnerUserId(chat, requesterUserId)

        chatExitRequestRepository.findByChatIdAndStatusAndType(
            chatId = chatId,
            status = ChatExitRequestStatus.PENDING,
            type = ChatExitRequestType.MUTUAL_CANCEL
        )?.let {
            check(it.requesterUserId == requesterUserId) {
                "A mutual cancellation request is already pending for chat $chatId"
            }
            return it
        }

        return chatExitRequestRepository.save(
            ChatExitRequest(
                chatId = chatId,
                requesterUserId = requesterUserId,
                responderUserId = responderUserId,
                type = ChatExitRequestType.MUTUAL_CANCEL,
                reason = reason,
                details = details
            )
        )
    }

    fun findExitRequests(
        chatId: UUID,
        userId: UUID
    ): List<ChatExitRequest> {
        val chat = findChatOrThrow(chatId)
        resolvePartnerUserId(chat, userId)
        return chatExitRequestRepository.findByChatIdOrderByCreatedAtDesc(chatId)
    }

    fun acceptMutualCancellation(
        chatId: UUID,
        requestId: UUID,
        responderUserId: UUID
    ): ChatExitOutcome {
        val chat = findChatOrThrow(chatId)
        validateActiveChatWindow(chat)
        val exitRequest = findExitRequestOrThrow(requestId)

        check(exitRequest.chatId == chatId) {
            "Exit request $requestId does not belong to chat $chatId"
        }
        check(exitRequest.type == ChatExitRequestType.MUTUAL_CANCEL) {
            "Exit request $requestId is not a mutual cancellation request"
        }
        check(exitRequest.status == ChatExitRequestStatus.PENDING) {
            "Exit request $requestId is not pending"
        }
        check(exitRequest.responderUserId == responderUserId) {
            "Only the requested participant can accept this cancellation"
        }

        exitRequest.status = ChatExitRequestStatus.ACCEPTED
        exitRequest.resolvedAt = OffsetDateTime.now()
        chatExitRequestRepository.save(exitRequest)

        finishCancelledChat(chat)

        return ChatExitOutcome(
            chat = chat,
            exitRequest = exitRequest,
            penaltyApplied = false,
            penalizedUserId = null
        )
    }

    fun rejectMutualCancellation(
        chatId: UUID,
        requestId: UUID,
        responderUserId: UUID
    ): ChatExitRequest {
        val chat = findChatOrThrow(chatId)
        validateActiveChatWindow(chat)
        val exitRequest = findExitRequestOrThrow(requestId)

        check(exitRequest.chatId == chatId) {
            "Exit request $requestId does not belong to chat $chatId"
        }
        check(exitRequest.type == ChatExitRequestType.MUTUAL_CANCEL) {
            "Exit request $requestId is not a mutual cancellation request"
        }
        check(exitRequest.status == ChatExitRequestStatus.PENDING) {
            "Exit request $requestId is not pending"
        }
        check(exitRequest.responderUserId == responderUserId) {
            "Only the requested participant can reject this cancellation"
        }

        exitRequest.status = ChatExitRequestStatus.REJECTED
        exitRequest.resolvedAt = OffsetDateTime.now()

        return chatExitRequestRepository.save(exitRequest)
    }

    fun cancelChatUnilaterally(
        chatId: UUID,
        userId: UUID,
        reason: ChatExitReason? = ChatExitReason.NO_LONGER_INTERESTED,
        details: String? = null
    ): ChatExitOutcome {
        val chat = findChatOrThrow(chatId)
        validateActiveChatWindow(chat)
        val responderUserId = resolvePartnerUserId(chat, userId)

        val shouldPenalize = shouldPenalizeCancellation(chat, userId)
        if (shouldPenalize) {
            penaltyService.createCancellationPenalty(userId = userId)
        }

        val exitRequest = chatExitRequestRepository.save(
            ChatExitRequest(
                chatId = chatId,
                requesterUserId = userId,
                responderUserId = responderUserId,
                type = ChatExitRequestType.UNILATERAL_CANCEL,
                status = ChatExitRequestStatus.ACCEPTED,
                reason = reason,
                details = details,
                resolvedAt = OffsetDateTime.now()
            )
        )

        finishCancelledChat(chat)

        return ChatExitOutcome(
            chat = chat,
            exitRequest = exitRequest,
            penaltyApplied = shouldPenalize,
            penalizedUserId = if (shouldPenalize) userId else null
        )
    }

    fun cancelChatForSafety(
        chatId: UUID,
        reporterUserId: UUID,
        reason: ChatExitReason = ChatExitReason.INAPPROPRIATE_BEHAVIOR,
        details: String? = null
    ): ChatExitOutcome {
        val chat = findChatOrThrow(chatId)
        validateActiveChatWindow(chat)
        val reportedUserId = resolvePartnerUserId(chat, reporterUserId)

        penaltyService.createSafetyReportPenalty(userId = reportedUserId)

        val exitRequest = chatExitRequestRepository.save(
            ChatExitRequest(
                chatId = chatId,
                requesterUserId = reporterUserId,
                responderUserId = reportedUserId,
                type = ChatExitRequestType.SAFETY_REPORT,
                status = ChatExitRequestStatus.ACCEPTED,
                reason = reason,
                details = details,
                resolvedAt = OffsetDateTime.now()
            )
        )

        finishCancelledChat(chat)

        return ChatExitOutcome(
            chat = chat,
            exitRequest = exitRequest,
            penaltyApplied = true,
            penalizedUserId = reportedUserId
        )
    }

    private fun findChatOrThrow(chatId: UUID): Chat {
        return chatRepository.findById(chatId)
            .orElseThrow {
                NoSuchElementException("Chat not found: $chatId")
            }
    }

    private fun findExitRequestOrThrow(requestId: UUID): ChatExitRequest =
        chatExitRequestRepository.findById(requestId)
            .orElseThrow {
                NoSuchElementException("Chat exit request not found: $requestId")
            }

    private fun resolvePartnerUserId(
        chat: Chat,
        userId: UUID
    ): UUID {
        val match = matchService.findByIdOrThrow(chat.matchId)
        return when (userId) {
            match.userAId -> match.userBId
            match.userBId -> match.userAId
            else -> error("User $userId does not belong to chat ${chat.id}")
        }
    }

    private fun shouldPenalizeCancellation(
        chat: Chat,
        userId: UUID
    ): Boolean {
        val minimum =
            when (chat.chatType) {
                ChatType.FIRST_CHAT -> firstChatMinMessagesBeforeFreeCancel
                ChatType.SECOND_CHAT -> secondChatMinMessagesBeforeFreeCancel
            }

        if (minimum <= 0) return false

        val sent =
            chatMessageRepository.countByChatSessionIdAndSenderId(
                chatSessionId = chat.id,
                senderId = userId
            )

        return sent < minimum
    }

    private fun finishCancelledChat(chat: Chat) {
        chat.status = ChatStatus.CANCELLED
        chat.endedAt = OffsetDateTime.now()
        chatRepository.save(chat)

        when (chat.chatType) {
            ChatType.FIRST_CHAT -> matchService.rejectChatPhase(chat.matchId)
            ChatType.SECOND_CHAT -> {
                val connectionId = checkNotNull(chat.connectionId) {
                    "SECOND_CHAT has no connectionId"
                }
                connectionService.closeConnection(connectionId)
            }
        }
    }

    private fun validateActiveChatWindow(chat: Chat) {
        check(chat.status == ChatStatus.ACTIVE) {
            "Chat ${chat.id} is not active (status: ${chat.status})"
        }

        check(OffsetDateTime.now().isBefore(chat.timeoutAt)) {
            "Chat ${chat.id} has timed out"
        }
    }
}
