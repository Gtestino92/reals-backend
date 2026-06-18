package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatExitRequest
import com.reals.backend.domain.ChatExitRequestCreationResult
import com.reals.backend.domain.ChatExitRequestStatus
import com.reals.backend.domain.ChatExitRequestType
import com.reals.backend.domain.ChatExitOutcome
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.MatchState
import com.reals.backend.repository.ChatExitRequestRepository
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.validation.PlainText
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
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
    private val secondChatMinMessagesBeforeFreeCancel: Int,

    @param:Value("\${chat.exit-request.mutual-timeout-seconds:20}")
    private val mutualCancellationTimeoutSeconds: Long
) {

    private companion object {
        const val DETAILS_MAX_LENGTH = 1000
    }

    fun requestMutualCancellation(
        chatId: UUID,
        requesterUserId: UUID,
        reason: ChatExitReason? = ChatExitReason.NO_LONGER_INTERESTED,
        details: String? = null
    ): ChatExitRequest =
        requestMutualCancellationWithResult(
            chatId = chatId,
            requesterUserId = requesterUserId,
            reason = reason,
            details = details
        ).exitRequest

    fun requestMutualCancellationWithResult(
        chatId: UUID,
        requesterUserId: UUID,
        reason: ChatExitReason? = ChatExitReason.NO_LONGER_INTERESTED,
        details: String? = null
    ): ChatExitRequestCreationResult {
        val chat = findChatOrThrow(chatId)
        validateActiveChatWindow(chat)
        validateExitActionAllowed(chat, requesterUserId)
        val responderUserId = resolvePartnerUserId(chat, requesterUserId)
        val normalizedDetails = normalizeDetails(details)

        chatExitRequestRepository.findByChatIdAndStatusAndType(
            chatId = chatId,
            status = ChatExitRequestStatus.PENDING,
            type = ChatExitRequestType.MUTUAL_CANCEL
        )?.let {
            check(it.requesterUserId == requesterUserId) {
                "A mutual cancellation request is already pending for chat $chatId"
            }
            return ChatExitRequestCreationResult(
                exitRequest = it,
                created = false
            )
        }

        return ChatExitRequestCreationResult(
            exitRequest = chatExitRequestRepository.save(
                ChatExitRequest(
                    chatId = chatId,
                    requesterUserId = requesterUserId,
                    responderUserId = responderUserId,
                    type = ChatExitRequestType.MUTUAL_CANCEL,
                    reason = reason,
                    details = normalizedDetails
                )
            ),
            created = true
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
        validateExitActionAllowed(chat, responderUserId)
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
    ): ChatExitOutcome {
        val chat = findChatOrThrow(chatId)
        validateActiveChatWindow(chat)
        validateExitActionAllowed(chat, responderUserId)
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
        chatExitRequestRepository.save(exitRequest)

        finishCancelledChat(chat)

        return ChatExitOutcome(
            chat = chat,
            exitRequest = exitRequest,
            penaltyApplied = false,
            penalizedUserId = null
        )
    }

    fun timeoutMutualCancellation(
        chatId: UUID,
        requestId: UUID,
        userId: UUID
    ): ChatExitOutcome {
        val chat = findChatOrThrow(chatId)
        validateActiveChatWindow(chat)
        validateExitActionAllowed(chat, userId)
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

        val now = OffsetDateTime.now()
        val timeoutAt = exitRequest.createdAt.plusSeconds(mutualCancellationTimeoutSeconds)

        check(!now.isBefore(timeoutAt)) {
            "Mutual cancellation request $requestId has not timed out"
        }

        exitRequest.status = ChatExitRequestStatus.TIMED_OUT
        exitRequest.resolvedAt = now
        chatExitRequestRepository.save(exitRequest)

        finishCancelledChat(chat)

        // Client-triggered mutual timeout means the pending request was not
        // answered in time. It is not a unilateral cancellation and must not
        // penalize the caller under future scoring semantics.
        return ChatExitOutcome(
            chat = chat,
            exitRequest = exitRequest,
            penaltyApplied = false,
            penalizedUserId = null
        )
    }

    fun cancelChatUnilaterally(
        chatId: UUID,
        userId: UUID,
        reason: ChatExitReason? = ChatExitReason.NO_LONGER_INTERESTED,
        details: String? = null
    ): ChatExitOutcome {
        val chat = findChatOrThrow(chatId)
        validateActiveChatWindow(chat)
        validateExitActionAllowed(chat, userId)
        val responderUserId = resolvePartnerUserId(chat, userId)
        val normalizedDetails = normalizeDetails(details)

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
                details = normalizedDetails,
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
        validateExitActionAllowed(chat, reporterUserId)
        val reportedUserId = resolvePartnerUserId(chat, reporterUserId)
        val normalizedDetails = normalizeDetails(details)

        require(!normalizedDetails.isNullOrBlank()) {
            "Safety cancellation details are required"
        }

        penaltyService.createSafetyReportPenalty(userId = reportedUserId)

        val exitRequest = chatExitRequestRepository.save(
            ChatExitRequest(
                chatId = chatId,
                requesterUserId = reporterUserId,
                responderUserId = reportedUserId,
                type = ChatExitRequestType.SAFETY_REPORT,
                status = ChatExitRequestStatus.ACCEPTED,
                reason = reason,
                details = normalizedDetails,
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
        return when (chat.chatType) {
            ChatType.FIRST_CHAT -> {
                val match = matchService.findByIdOrThrow(chat.matchId)
                when (userId) {
                    match.userAId -> match.userBId
                    match.userBId -> match.userAId
                    else -> throw AccessDeniedException(
                        "User $userId does not belong to chat ${chat.id}"
                    )
                }
            }

            ChatType.SECOND_CHAT -> {
                val connectionId = checkNotNull(chat.connectionId) {
                    "SECOND_CHAT ${chat.id} has no connectionId"
                }
                val connection = connectionService.findByIdOrThrow(connectionId)
                when (userId) {
                    connection.userAId -> connection.userBId
                    connection.userBId -> connection.userAId
                    else -> throw AccessDeniedException(
                        "User $userId does not belong to chat ${chat.id}"
                    )
                }
            }
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

    private fun validateExitActionAllowed(
        chat: Chat,
        actingUserId: UUID
    ) {
        when (chat.chatType) {
            ChatType.FIRST_CHAT -> validateFirstChatExitContext(chat, actingUserId)
            ChatType.SECOND_CHAT -> validateSecondChatExitContext(chat, actingUserId)
        }
    }

    private fun validateFirstChatExitContext(
        chat: Chat,
        actingUserId: UUID
    ) {
        val match = matchService.findByIdOrThrow(chat.matchId)

        check(match.state == MatchState.CHAT_ACTIVE) {
            "Cannot act on chat ${chat.id}: match ${match.id} is not in CHAT_ACTIVE"
        }

        if (actingUserId != match.userAId && actingUserId != match.userBId) {
            throw AccessDeniedException("User $actingUserId does not belong to match ${match.id}")
        }

        val firstChat =
            chatRepository.findByMatchIdAndChatType(
                matchId = chat.matchId,
                chatType = ChatType.FIRST_CHAT
            )

        check(firstChat?.id == chat.id) {
            "Cannot act on chat ${chat.id}: it is not the FIRST_CHAT for match ${match.id}"
        }
    }

    private fun validateSecondChatExitContext(
        chat: Chat,
        actingUserId: UUID
    ) {
        val connectionId = checkNotNull(chat.connectionId) {
            "SECOND_CHAT ${chat.id} has no connectionId"
        }
        val connection = connectionService.findByIdOrThrow(connectionId)

        if (actingUserId != connection.userAId && actingUserId != connection.userBId) {
            throw AccessDeniedException("User $actingUserId does not belong to connection $connectionId")
        }

        check(
            connection.state in setOf(
                ConnectionState.SECOND_CHAT_AVAILABLE,
                ConnectionState.SECOND_CHAT
            )
        ) {
            "Cannot act on chat ${chat.id}: connection $connectionId is not in second-chat phase"
        }

        val secondChat =
            chatRepository.findByConnectionIdAndChatType(
                connectionId = connectionId,
                chatType = ChatType.SECOND_CHAT
            )

        check(secondChat?.id == chat.id) {
            "Cannot act on chat ${chat.id}: it is not the SECOND_CHAT for connection $connectionId"
        }
    }

    private fun normalizeDetails(details: String?): String? {
        val normalized = details?.trim()?.takeIf { it.isNotBlank() }

        if (normalized != null) {
            require(normalized.length <= DETAILS_MAX_LENGTH) {
                "Details must be at most $DETAILS_MAX_LENGTH characters"
            }

            PlainText.requireValid("Details", normalized)
        }

        return normalized
    }
}
