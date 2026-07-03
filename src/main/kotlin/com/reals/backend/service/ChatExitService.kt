package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatEndReason
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
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.repository.ChatExitRequestRepository
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.service.reports.SafetyReportService
import com.reals.backend.service.reliability.UserReliabilityScoreService
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class ChatExitService(
    private val chatRepository: ChatRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val chatExitRequestRepository: ChatExitRequestRepository,
    private val matchService: MatchService,
    private val penaltyService: PenaltyService,
    private val safetyReportService: SafetyReportService,
    private val userBlockService: UserBlockService,
    private val connectionService: ConnectionService,
    private val auditEventService: AuditEventService,
    private val userReliabilityScoreService: UserReliabilityScoreService,

    @param:Value("\${chat.first-chat.min-messages-before-free-cancel:0}")
    private val firstChatMinMessagesBeforeFreeCancel: Int,

    @param:Value("\${chat.second-chat.min-messages-before-free-cancel:0}")
    private val secondChatMinMessagesBeforeFreeCancel: Int,

    @param:Value("\${chat.exit-request.mutual-timeout-seconds:20}")
    private val mutualCancellationTimeoutSeconds: Long,

    @param:Value("\${user-reliability.first-chat.min-participation-messages-per-user:2}")
    private val reliabilityMinParticipationMessagesPerUser: Int,

    @param:Value("\${user-reliability.first-chat.min-participation-minutes:5}")
    private val reliabilityMinParticipationMinutes: Long
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
            if (it.requesterUserId != requesterUserId) {
                throw DomainConflictException(
                    code = DomainErrorCode.CHAT_EXIT_REQUEST_ALREADY_PENDING,
                    message = "A chat exit request is already pending"
                )
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

        validateActionableMutualCancellationRequest(
            exitRequest = exitRequest,
            chatId = chatId,
            responderUserId = responderUserId
        )

        exitRequest.status = ChatExitRequestStatus.ACCEPTED
        exitRequest.resolvedAt = OffsetDateTime.now()
        chatExitRequestRepository.save(exitRequest)

        finishCancelledChat(
            chat = chat,
            endedReason = ChatEndReason.MUTUAL_CANCEL,
            actorUserId = responderUserId
        )

        recordFirstChatMutualNoSparkClosure(chat)

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

        validateActionableMutualCancellationRequest(
            exitRequest = exitRequest,
            chatId = chatId,
            responderUserId = responderUserId
        )

        exitRequest.status = ChatExitRequestStatus.REJECTED
        exitRequest.resolvedAt = OffsetDateTime.now()
        chatExitRequestRepository.save(exitRequest)

        finishCancelledChat(
            chat = chat,
            endedReason = ChatEndReason.MUTUAL_CANCEL,
            actorUserId = responderUserId
        )

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

        if (
            exitRequest.chatId != chatId ||
            exitRequest.type != ChatExitRequestType.MUTUAL_CANCEL ||
            exitRequest.status != ChatExitRequestStatus.PENDING
        ) {
            throw exitRequestNotAvailable()
        }

        val now = OffsetDateTime.now()
        val timeoutAt = exitRequest.createdAt.plusSeconds(mutualCancellationTimeoutSeconds)

        if (now.isBefore(timeoutAt)) {
            throw exitRequestNotAvailable()
        }

        exitRequest.status = ChatExitRequestStatus.TIMED_OUT
        exitRequest.resolvedAt = now
        chatExitRequestRepository.save(exitRequest)

        finishCancelledChat(
            chat = chat,
            endedReason = ChatEndReason.MUTUAL_CANCEL,
            actorUserId = userId
        )

        if (chat.chatType == ChatType.FIRST_CHAT) {
            userReliabilityScoreService.recordEvent(
                userId = exitRequest.responderUserId,
                eventType = UserReliabilityEventType.FIRST_CHAT_MUTUAL_CLOSE_REQUEST_IGNORED,
                relatedMatchId = chat.matchId,
                relatedChatId = chat.id
            )
        }

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

        finishCancelledChat(
            chat = chat,
            endedReason = ChatEndReason.UNILATERAL_CANCEL,
            actorUserId = userId
        )

        recordFirstChatUnilateralClosure(
            chat = chat,
            closingUserId = userId,
            counterpartUserId = responderUserId
        )

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

        if (normalizedDetails.isNullOrBlank()) {
            throw invalidChatMessage()
        }

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

        val report = safetyReportService.createPendingReport(
            chat = chat,
            reporterUserId = reporterUserId,
            reportedUserId = reportedUserId,
            reason = reason,
            details = normalizedDetails
        )

        userBlockService.blockUser(
            blockerUserId = reporterUserId,
            blockedUserId = reportedUserId,
            source = UserBlockSource.SAFETY_REPORT,
            sourceReportId = report.id
        )

        finishCancelledChat(
            chat = chat,
            endedReason = ChatEndReason.SAFETY_REPORT,
            actorUserId = reporterUserId
        )

        return ChatExitOutcome(
            chat = chat,
            exitRequest = exitRequest,
            penaltyApplied = false,
            penalizedUserId = null
        )
    }

    private fun recordFirstChatMutualNoSparkClosure(chat: Chat) {
        if (chat.chatType != ChatType.FIRST_CHAT) {
            return
        }

        val match = matchService.findByIdOrThrow(chat.matchId)
        listOf(match.userAId, match.userBId).forEach { userId ->
            userReliabilityScoreService.recordEvent(
                userId = userId,
                eventType = UserReliabilityEventType.FIRST_CHAT_MUTUAL_NO_SPARK_CLOSURE,
                relatedMatchId = match.id,
                relatedChatId = chat.id
            )
        }
    }

    private fun recordFirstChatUnilateralClosure(
        chat: Chat,
        closingUserId: UUID,
        counterpartUserId: UUID
    ) {
        if (chat.chatType != ChatType.FIRST_CHAT) {
            return
        }

        val now = OffsetDateTime.now()
        val elapsedThresholdMet = !chat.startedAt.plusMinutes(reliabilityMinParticipationMinutes).isAfter(now)
        val closingUserMessages =
            chatMessageRepository.countByChatSessionIdAndSenderId(
                chatSessionId = chat.id,
                senderId = closingUserId
            )
        val counterpartMessages =
            chatMessageRepository.countByChatSessionIdAndSenderId(
                chatSessionId = chat.id,
                senderId = counterpartUserId
            )

        if (elapsedThresholdMet && closingUserMessages > 0L && counterpartMessages == 0L) {
            userReliabilityScoreService.recordEvent(
                userId = counterpartUserId,
                eventType = UserReliabilityEventType.FIRST_CHAT_CLOSED_AFTER_COUNTERPARTY_INACTIVE,
                relatedMatchId = chat.matchId,
                relatedChatId = chat.id
            )
            return
        }

        val minimumParticipationMet =
            elapsedThresholdMet &&
                closingUserMessages >= reliabilityMinParticipationMessagesPerUser.toLong() &&
                counterpartMessages >= reliabilityMinParticipationMessagesPerUser.toLong()

        userReliabilityScoreService.recordEvent(
            userId = closingUserId,
            eventType = if (minimumParticipationMet) {
                UserReliabilityEventType.FIRST_CHAT_UNILATERAL_CLOSE_AFTER_MINIMUM_PARTICIPATION
            } else {
                UserReliabilityEventType.FIRST_CHAT_EARLY_UNILATERAL_CLOSE
            },
            relatedMatchId = chat.matchId,
            relatedChatId = chat.id
        )
    }

    private fun findChatOrThrow(chatId: UUID): Chat {
        return chatRepository.findById(chatId)
            .orElseThrow {
                DomainNotFoundException(
                    code = DomainErrorCode.CHAT_NOT_FOUND,
                    message = "Chat was not found"
                )
            }
    }

    private fun findExitRequestOrThrow(requestId: UUID): ChatExitRequest =
        chatExitRequestRepository.findById(requestId)
            .orElseThrow {
                DomainNotFoundException(
                    code = DomainErrorCode.CHAT_EXIT_REQUEST_NOT_FOUND,
                    message = "Chat exit request was not found"
                )
            }

    private fun validateActionableMutualCancellationRequest(
        exitRequest: ChatExitRequest,
        chatId: UUID,
        responderUserId: UUID
    ) {
        if (
            exitRequest.chatId != chatId ||
            exitRequest.type != ChatExitRequestType.MUTUAL_CANCEL ||
            exitRequest.status != ChatExitRequestStatus.PENDING ||
            exitRequest.responderUserId != responderUserId
        ) {
            throw exitRequestNotAvailable()
        }
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
                val connectionId = chat.connectionId ?: throw chatNotAvailable()
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

    private fun finishCancelledChat(
        chat: Chat,
        endedReason: ChatEndReason,
        actorUserId: UUID? = null
    ) {
        chat.status = ChatStatus.CANCELLED
        chat.endedAt = OffsetDateTime.now()
        chat.endedReason = endedReason
        chatRepository.save(chat)
        auditEventService.record(
            eventType = AuditEventType.CHAT_ENDED,
            aggregateType = AuditAggregateType.CHAT,
            aggregateId = chat.id,
            actorUserId = actorUserId,
            metadata = mapOf(
                "chatType" to chat.chatType.name,
                "status" to chat.status.name,
                "endedReason" to endedReason.name,
                "matchId" to chat.matchId,
                "connectionId" to chat.connectionId
            )
        )

        when (chat.chatType) {
            ChatType.FIRST_CHAT -> matchService.rejectChatPhase(chat.matchId)
            ChatType.SECOND_CHAT -> {
                val connectionId = chat.connectionId ?: throw chatNotAvailable()
                connectionService.closeConnection(connectionId)
            }
        }
    }

    private fun validateActiveChatWindow(chat: Chat) {
        if (chat.status == ChatStatus.EXPIRED || chat.status == ChatStatus.ABANDONED) {
            throw chatExpired()
        }

        if (chat.status != ChatStatus.ACTIVE) {
            throw chatNotAvailable()
        }

        if (!OffsetDateTime.now().isBefore(chat.timeoutAt)) {
            throw chatExpired()
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

        if (match.state != MatchState.CHAT_ACTIVE) {
            throw chatNotAvailable()
        }

        if (actingUserId != match.userAId && actingUserId != match.userBId) {
            throw AccessDeniedException("User $actingUserId does not belong to match ${match.id}")
        }

        val firstChat =
            chatRepository.findByMatchIdAndChatType(
                matchId = chat.matchId,
                chatType = ChatType.FIRST_CHAT
            )

        if (firstChat?.id != chat.id) {
            throw chatNotAvailable()
        }
    }

    private fun validateSecondChatExitContext(
        chat: Chat,
        actingUserId: UUID
    ) {
        val connectionId = chat.connectionId ?: throw chatNotAvailable()
        val connection = connectionService.findByIdOrThrow(connectionId)

        if (actingUserId != connection.userAId && actingUserId != connection.userBId) {
            throw AccessDeniedException("User $actingUserId does not belong to connection $connectionId")
        }

        if (
            connection.state !in setOf(
                ConnectionState.SECOND_CHAT_AVAILABLE,
                ConnectionState.SECOND_CHAT
            )
        ) {
            throw chatNotAvailable()
        }

        val secondChat =
            chatRepository.findByConnectionIdAndChatType(
                connectionId = connectionId,
                chatType = ChatType.SECOND_CHAT
            )

        if (secondChat?.id != chat.id) {
            throw chatNotAvailable()
        }
    }

    private fun normalizeDetails(details: String?): String? {
        val normalized = details?.trim()?.takeIf { it.isNotBlank() }

        if (normalized != null) {
            if (normalized.length > DETAILS_MAX_LENGTH) {
                throw invalidChatMessage()
            }

            if (normalized.any { it.isISOControl() || it == '<' || it == '>' }) {
                throw invalidChatMessage()
            }
        }

        return normalized
    }

    private fun chatNotAvailable(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_NOT_AVAILABLE,
            message = "Chat is not available"
        )

    private fun chatExpired(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_EXPIRED,
            message = "Chat has expired"
        )

    private fun exitRequestNotAvailable(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_EXIT_REQUEST_NOT_AVAILABLE,
            message = "Chat exit request is not available"
        )

    private fun invalidChatMessage(): DomainBadRequestException =
        DomainBadRequestException(
            code = DomainErrorCode.CHAT_MESSAGE_INVALID,
            message = "Chat message is invalid"
        )
}
