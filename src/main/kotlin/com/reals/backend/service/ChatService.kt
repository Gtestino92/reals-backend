package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatDecision
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatExitRequestStatus
import com.reals.backend.domain.ChatExitRequestType
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.ChatParticipantDecisionStatus
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.SecondChatAttendanceStatus
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.repository.ChatDecisionRepository
import com.reals.backend.repository.ChatExitRequestRepository
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.SecondChatParticipationRepository
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.service.reliability.UserReliabilityScoreService
import jakarta.transaction.Transactional
import org.springframework.data.domain.PageRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class ChatService(
    private val chatRepository: ChatRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val chatExitRequestRepository: ChatExitRequestRepository,
    private val chatDecisionRepository: ChatDecisionRepository,
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val secondChatParticipationRepository: SecondChatParticipationRepository,
    private val matchService: MatchService,
    private val visualReviewService: VisualReviewService,
    private val penaltyService: PenaltyService,
    private val connectionService: ConnectionService,
    private val chatExitService: ChatExitService,
    private val firstChatGuidanceService: FirstChatGuidanceService,
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService,
    private val userReliabilityScoreService: UserReliabilityScoreService,
    private val userBlockService: UserBlockService,
    private val secondChatConversationLifecycleService: SecondChatConversationLifecycleService,
    private val chatAudioPolicyService: ChatAudioPolicyService,
    private val mediaCleanupTaskService: MediaCleanupTaskService,
    private val readMetrics: ReadMetrics,

    @param:Value("\${chat.first-chat.duration-minutes:15}")
    private val firstChatDurationMinutes: Long,

    @param:Value("\${chat.second-chat.duration-minutes:2880}")
    private val secondChatDurationMinutes: Long,

    @param:Value("\${chat.second-chat.read-only-retention-minutes:1440}")
    private val secondChatReadOnlyRetentionMinutes: Long,

    @param:Value("\${chat.first-chat.min-messages-per-user:0}")
    private val minMessagesPerUser: Int,

    @param:Value("\${chat.first-chat.inactivity-threshold-minutes:5}")
    private val firstChatInactivityThresholdMinutes: Long,

    @param:Value("\${chat.messages.page-limit-default:200}")
    private val defaultMessagePageLimit: Int
) {

    sealed interface SendMessageResult {
        data class Sent(val message: ChatMessage) : SendMessageResult
        data class RejectedAfterResolution(
            val code: DomainErrorCode,
            val message: String
        ) : SendMessageResult
    }

    sealed interface SendAudioMessageResult {
        data class Created(val message: ChatMessage) : SendAudioMessageResult
        data class Replayed(val message: ChatMessage) : SendAudioMessageResult
        data class RejectedAfterResolution(
            val code: DomainErrorCode,
            val message: String
        ) : SendAudioMessageResult
    }

    private companion object {
        const val MESSAGE_MAX_LENGTH = 1000
        const val MESSAGE_PAGE_LIMIT_MAX = 500
    }

    data class ParticipantDecisionStatuses(
        val myDecision: ChatParticipantDecisionStatus,
        val partnerDecision: ChatParticipantDecisionStatus
    )

    data class ChatMessagesPage(
        val messages: List<ChatMessage>,
        val hasMore: Boolean
    )

    fun findByIdOrThrow(chatId: UUID): Chat {
        return chatRepository.findById(chatId)
            .orElseThrow {
                chatNotFound()
            }
    }

    fun findByIdForUserOrThrow(
        chatId: UUID,
        userId: UUID
    ): Chat {
        val chat = findByIdOrThrow(chatId)
        validateChatParticipant(chat, userId)
        return chat
    }

    fun startFirstChat(matchId: UUID): Chat {
        val now = OffsetDateTime.now()
        val match = matchService.findByIdOrThrow(matchId)
        userBlockService.requirePairNotBlocked(match.userAId, match.userBId)

        val chat = chatRepository.save(
            Chat(
                matchId = matchId,
                chatType = ChatType.FIRST_CHAT,
                startedAt = now,
                timeoutAt = now.plusMinutes(firstChatDurationMinutes)
            )
        )
        firstChatGuidanceService.initializeForFirstChat(
            chat = chat,
            now = now
        )
        homeStateInvalidationService.bumpBoth(
            userAId = match.userAId,
            userBId = match.userBId,
            reason = "first_chat_started"
        )
        return chat
    }

    fun inactivityExpiresAt(chat: Chat): OffsetDateTime? {
        if (chat.chatType != ChatType.FIRST_CHAT) {
            return null
        }

        return (chat.lastMessageAt ?: chat.startedAt)
            .plusMinutes(firstChatInactivityThresholdMinutes)
    }

    fun startSecondChat(
        matchId: UUID,
        connectionId: UUID,
        availableAt: OffsetDateTime,
        activatedAt: OffsetDateTime = OffsetDateTime.now()
    ): Chat {
        val connection = connectionService.lockByIdOrThrow(connectionId)
        userBlockService.requirePairNotBlocked(connection.userAId, connection.userBId)
        chatRepository
            .findByConnectionIdAndChatType(connectionId, ChatType.SECOND_CHAT)
            ?.let { return it }

        val chat = chatRepository.saveAndFlush(
            Chat(
                matchId = matchId,
                connectionId = connectionId,
                chatType = ChatType.SECOND_CHAT,
                status = ChatStatus.ACTIVE,
                startedAt = activatedAt,
                availableAt = availableAt,
                activatedAt = activatedAt,
                timeoutAt = availableAt.plusMinutes(secondChatDurationMinutes)
            )
        )
        homeStateInvalidationService.bumpBoth(
            userAId = connection.userAId,
            userBId = connection.userBId,
            reason = "second_chat_started"
        )
        return chat
    }

    fun sendMessage(
        chatId: UUID,
        senderId: UUID,
        content: String
    ): ChatMessage =
        when (val result = sendMessageWithResult(chatId, senderId, content)) {
            is SendMessageResult.Sent -> result.message
            is SendMessageResult.RejectedAfterResolution ->
                throw DomainConflictException(code = result.code, message = result.message)
        }

    fun sendMessageWithResult(
        chatId: UUID,
        senderId: UUID,
        content: String,
        now: OffsetDateTime = OffsetDateTime.now()
    ): SendMessageResult {
        val normalizedContent = normalizeMessageContent(content)

        val chat = findByIdForUpdateOrThrow(chatId)

        requireChatPairNotBlocked(chat)
        validateActiveChatWindow(chat)
        validateChatParticipant(chat, senderId)
        requireSecondChatJoinedForMessage(chat, senderId)
        if (chat.chatType == ChatType.FIRST_CHAT) {
            requireNoPendingMutualCancellation(chat.id)
        }

        when (
            val lifecycleResult =
                secondChatConversationLifecycleService.beforeSecondChatMessage(
                    chat = chat,
                    senderId = senderId,
                    now = now
                )
        ) {
            is SecondChatConversationLifecycleService.SecondChatMessageResult.Continue -> Unit
            is SecondChatConversationLifecycleService.SecondChatMessageResult.RejectedAfterResolution ->
                return SendMessageResult.RejectedAfterResolution(
                    code = lifecycleResult.code,
                    message = lifecycleResult.message
                )
        }

        val message =
            chatMessageRepository.save(
                ChatMessage(
                    chatSessionId = chat.id,
                    senderId = senderId,
                    messageType = ChatMessageType.TEXT,
                    content = normalizedContent,
                    sentAt = now
                )
            )

        chat.lastMessageAt = maxOf(chat.lastMessageAt ?: message.sentAt, message.sentAt)
        if (chat.chatType == ChatType.SECOND_CHAT) {
            chat.lastMessageSenderId = senderId
        }
        chatRepository.save(chat)

        return SendMessageResult.Sent(message)
    }

    fun findAudioMessageReplayOrThrowOnConflict(
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID,
        audioSha256: String
    ): ChatMessage? {
        val chat = findByIdOrThrow(chatId)
        validateChatParticipant(chat, senderId)
        val existing =
            chatMessageRepository.findByChatSessionIdAndSenderIdAndClientMessageId(
                chatSessionId = chatId,
                senderId = senderId,
                clientMessageId = clientMessageId
            ) ?: return null

        if (existing.audioSha256 != audioSha256) {
            throw DomainConflictException(
                code = DomainErrorCode.CHAT_MESSAGE_IDEMPOTENCY_CONFLICT,
                message = "Client message id was already used with different audio content"
            )
        }

        return existing
    }

    fun preflightNewAudioMessage(
        chatId: UUID,
        senderId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ) {
        val chat = findByIdOrThrow(chatId)
        validateChatParticipant(chat, senderId)
        requireChatPairNotBlocked(chat)
        validateActiveChatWindowSideEffectFree(chat, now)
        requireSecondChatJoinedForMessage(chat, senderId)
        if (chat.chatType == ChatType.FIRST_CHAT) {
            requireNoPendingMutualCancellation(chat.id)
        }
        chatAudioPolicyService.requireAudioEnabled(
            chat = chat,
            userId = senderId,
            now = now
        )
    }

    fun sendAudioMessageWithResult(
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID,
        audioContentType: String,
        audioSizeBytes: Long,
        audioDurationMillis: Long,
        audioSha256: String,
        audioBucket: String,
        audioObjectKey: String,
        cleanupTaskId: UUID,
        messageId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): SendAudioMessageResult {
        val chat = findByIdForUpdateOrThrow(chatId)
        validateChatParticipant(chat, senderId)

        chatMessageRepository.findByChatSessionIdAndSenderIdAndClientMessageId(
            chatSessionId = chatId,
            senderId = senderId,
            clientMessageId = clientMessageId
        )?.let { existing ->
            if (existing.audioSha256 != audioSha256) {
                throw DomainConflictException(
                    code = DomainErrorCode.CHAT_MESSAGE_IDEMPOTENCY_CONFLICT,
                    message = "Client message id was already used with different audio content"
                )
            }
            return SendAudioMessageResult.Replayed(existing)
        }

        requireChatPairNotBlocked(chat)
        validateActiveChatWindow(chat)
        requireSecondChatJoinedForMessage(chat, senderId)
        if (chat.chatType == ChatType.FIRST_CHAT) {
            requireNoPendingMutualCancellation(chat.id)
        }

        when (
            val lifecycleResult =
                secondChatConversationLifecycleService.beforeSecondChatMessage(
                    chat = chat,
                    senderId = senderId,
                    now = now
                )
        ) {
            is SecondChatConversationLifecycleService.SecondChatMessageResult.Continue -> Unit
            is SecondChatConversationLifecycleService.SecondChatMessageResult.RejectedAfterResolution ->
                return SendAudioMessageResult.RejectedAfterResolution(
                    code = lifecycleResult.code,
                    message = lifecycleResult.message
                )
        }

        chatAudioPolicyService.requireAudioEnabled(
            chat = chat,
            userId = senderId,
            now = now
        )

        val message =
            chatMessageRepository.saveAndFlush(
                ChatMessage(
                    id = messageId,
                    chatSessionId = chat.id,
                    senderId = senderId,
                    messageType = ChatMessageType.AUDIO,
                    clientMessageId = clientMessageId,
                    content = null,
                    audioBucket = audioBucket,
                    audioObjectKey = audioObjectKey,
                    audioContentType = audioContentType,
                    audioSizeBytes = audioSizeBytes,
                    audioDurationMillis = audioDurationMillis,
                    audioSha256 = audioSha256,
                    sentAt = now
                )
            )

        chat.lastMessageAt = maxOf(chat.lastMessageAt ?: message.sentAt, message.sentAt)
        if (chat.chatType == ChatType.SECOND_CHAT) {
            chat.lastMessageSenderId = senderId
        }
        chatRepository.save(chat)
        mediaCleanupTaskService.deleteTaskInCurrentTransaction(cleanupTaskId)

        return SendAudioMessageResult.Created(message)
    }

    fun recordChatDecision(
        matchId: UUID,
        userId: UUID,
        decision: ChatContinueDecision
    ) {
        val match = matchService.findByIdOrThrow(matchId)
        if (decision == ChatContinueDecision.APPROVED) {
            userBlockService.requirePairNotBlocked(match.userAId, match.userBId)
        }

        if (match.state != MatchState.CHAT_ACTIVE) {
            throw chatDecisionNotAvailable()
        }

        val chat = findActiveFirstChatForUpdateOrThrow(matchId)

        requireNoPendingMutualCancellation(chat.id)

        if (decision == ChatContinueDecision.REJECTED) {
            chatExitService.cancelChatUnilaterally(
                chatId = chat.id,
                userId = userId,
                reason = ChatExitReason.NO_LONGER_INTERESTED
            )
            return
        }

        val chatDecision =
            chatDecisionRepository.findByChatId(chat.id)
                ?: chatDecisionRepository.save(
                    ChatDecision(
                        chatId = chat.id,
                        matchId = match.id
                    )
                )

        if (minMessagesPerUser > 0) {
            val sent =
                chatMessageRepository.countByChatSessionIdAndSenderId(
                    chatSessionId = chat.id,
                    senderId = userId
                )

            if (sent < minMessagesPerUser) {
                throw DomainConflictException(
                    code = DomainErrorCode.CHAT_MIN_MESSAGES_REQUIRED,
                    message = "Minimum chat messages are required before approval"
                )
            }
        }

        when (userId) {
            match.userAId -> {
                if (chatDecision.userADecision != null) {
                    throw chatDecisionAlreadySubmitted()
                }
                chatDecision.userADecision = decision
            }

            match.userBId -> {
                if (chatDecision.userBDecision != null) {
                    throw chatDecisionAlreadySubmitted()
                }
                chatDecision.userBDecision = decision
            }

            else -> throw AccessDeniedException("User $userId does not belong to match $matchId")
        }

        chatDecision.updatedAt = OffsetDateTime.now()
        chatDecisionRepository.save(chatDecision)

        val aDecision = chatDecision.userADecision
        val bDecision = chatDecision.userBDecision

        if (aDecision != null && bDecision != null) {
            chat.status = ChatStatus.FINISHED
            chat.endedAt = OffsetDateTime.now()
            chat.endedReason = ChatEndReason.SYSTEM_CLOSED
            chatRepository.save(chat)
            recordChatEnded(chat)

            listOf(match.userAId, match.userBId).forEach { participantId ->
                userReliabilityScoreService.recordEvent(
                    userId = participantId,
                    eventType = UserReliabilityEventType.FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION,
                    relatedMatchId = match.id,
                    relatedChatId = chat.id
                )
            }

            matchService.transitionToVisualPhase(matchId)
            visualReviewService.initializeForMatch(matchId)
        }

        homeStateInvalidationService.bumpBoth(
            userAId = match.userAId,
            userBId = match.userBId,
            reason = "first_chat_decision_recorded"
        )
    }

    fun endChat(
        chatId: UUID,
        finalStatus: ChatStatus,
        endedReason: ChatEndReason,
        abandonedUserIds: List<UUID> = emptyList()
    ): Boolean {
        require(finalStatus == ChatStatus.EXPIRED || finalStatus == ChatStatus.ABANDONED) {
            "endChat only accepts EXPIRED or ABANDONED, got $finalStatus"
        }
        require(
            (finalStatus == ChatStatus.EXPIRED && endedReason == ChatEndReason.ABSOLUTE_TIMEOUT) ||
                (finalStatus == ChatStatus.ABANDONED && endedReason == ChatEndReason.INACTIVITY_TIMEOUT)
        ) {
            "Invalid endedReason $endedReason for finalStatus $finalStatus"
        }

        val chat = findByIdOrThrow(chatId)

        if (chat.status != ChatStatus.ACTIVE) return false

        chat.status = finalStatus
        chat.endedAt = OffsetDateTime.now()
        chat.endedReason = endedReason
        chatRepository.save(chat)
        recordChatEnded(chat)

        when (chat.chatType) {
            ChatType.FIRST_CHAT -> {
                recordFirstChatExpirationReliability(chat)
                matchService.expireMatch(chat.matchId)
            }

            ChatType.SECOND_CHAT -> {
                if (finalStatus == ChatStatus.ABANDONED) {
                    abandonedUserIds.forEach {
                        penaltyService.createAbandonmentPenalty(userId = it)
                    }
                }

                chat.connectionId?.let {
                    connectionService.closeConnection(connectionId = it)
                }
            }
        }

        return true
    }

    fun getMessages(
        chatId: UUID,
        userId: UUID,
        limit: Int? = null
    ): List<ChatMessage> =
        readMetrics.recordChatMessageRead(ReadMetrics.CHAT_MODE_INITIAL) {
            val messages = getMessagesMeasured(
                chatId = chatId,
                userId = userId,
                limit = limit
            )
            readMetrics.recordReturnedChatMessages(
                mode = ReadMetrics.CHAT_MODE_INITIAL,
                count = messages.size
            )
            messages
        }

    private fun getMessagesMeasured(
        chatId: UUID,
        userId: UUID,
        limit: Int?
    ): List<ChatMessage> {
        val chat = findByIdOrThrow(chatId)
        validateChatParticipant(chat, userId)
        validateChatReadable(chat)
        val pageLimit = resolveMessagePageLimit(limit)

        return chatMessageRepository.findByChatSessionIdOrderBySentAtDescIdDesc(
            chatSessionId = chatId,
            pageable = PageRequest.of(0, pageLimit)
        ).asReversed()
    }

    fun getFirstChatGuidanceState(
        chat: Chat,
        userId: UUID
    ): FirstChatGuidanceState? =
        firstChatGuidanceService.findStateForUser(
            chat = chat,
            userId = userId
        )

    fun requestFirstChatGuidanceNext(
        chatId: UUID,
        userId: UUID
    ): FirstChatGuidanceState {
        val chat = findByIdForUpdateOrThrow(chatId)
        requireChatPairNotBlocked(chat)
        validateChatParticipant(chat, userId)

        if (chat.chatType != ChatType.FIRST_CHAT) {
            throw chatNotAvailable()
        }

        validateActiveChatWindow(chat)
        requireNoPendingMutualCancellation(chat.id)

        return firstChatGuidanceService.requestNext(
            chat = chat,
            userId = userId
        )
    }

    fun getMessagesAfter(
        chatId: UUID,
        userId: UUID,
        afterMessageId: UUID,
        limit: Int? = null
    ): ChatMessagesPage =
        readMetrics.recordChatMessageRead(ReadMetrics.CHAT_MODE_INCREMENTAL) {
            val page = getMessagesAfterMeasured(
                chatId = chatId,
                userId = userId,
                afterMessageId = afterMessageId,
                limit = limit
            )
            readMetrics.recordReturnedChatMessages(
                mode = ReadMetrics.CHAT_MODE_INCREMENTAL,
                count = page.messages.size
            )
            page
        }

    private fun getMessagesAfterMeasured(
        chatId: UUID,
        userId: UUID,
        afterMessageId: UUID,
        limit: Int?
    ): ChatMessagesPage {
        val chat = findByIdOrThrow(chatId)
        validateChatParticipant(chat, userId)
        validateChatReadable(chat)
        val pageLimit = resolveMessagePageLimit(limit)

        val afterMessage =
            chatMessageRepository.findById(afterMessageId)
                .orElseThrow {
                    chatNotAvailable()
                }

        if (afterMessage.chatSessionId != chatId) {
            throw chatNotAvailable()
        }

        val page = chatMessageRepository.findPageAfterCursor(
            chatSessionId = chatId,
            cursorId = afterMessage.id,
            messageId = afterMessage.id.toString(),
            pageable = PageRequest.of(0, pageLimit + 1)
        )

        return ChatMessagesPage(
            messages = page.take(pageLimit),
            hasMore = page.size > pageLimit
        )
    }

    fun getFirstChatDecisionStatuses(
        matchId: UUID,
        userId: UUID
    ): ParticipantDecisionStatuses {
        val match = matchService.findByIdOrThrow(matchId)
        val chat = findActiveFirstChatOrThrow(matchId)
        validateChatParticipant(chat, userId)

        val chatDecision = chatDecisionRepository.findByChatId(chat.id)

        val userADecision = resolveParticipantDecisionStatus(
            chat = chat,
            userId = match.userAId,
            chatDecisionValue = chatDecision?.userADecision
        )
        val userBDecision = resolveParticipantDecisionStatus(
            chat = chat,
            userId = match.userBId,
            chatDecisionValue = chatDecision?.userBDecision
        )

        return when (userId) {
            match.userAId -> ParticipantDecisionStatuses(
                myDecision = userADecision,
                partnerDecision = userBDecision
            )

            match.userBId -> ParticipantDecisionStatuses(
                myDecision = userBDecision,
                partnerDecision = userADecision
            )

            else -> throw AccessDeniedException("User $userId does not belong to match $matchId")
        }
    }

    fun findInactiveChats(inactivityMinutes: Long): List<Chat> {
        val threshold = OffsetDateTime.now().minusMinutes(inactivityMinutes)
        return chatRepository.findInactiveActiveChats(threshold)
    }

    fun findInactiveChatIds(
        threshold: OffsetDateTime,
        limit: Int
    ): List<UUID> {
        require(limit > 0) { "Inactive chat candidate limit must be positive" }
        return chatRepository.findInactiveActiveChatIds(
            threshold = threshold,
            pageable = PageRequest.of(0, limit)
        )
    }

    fun findTimedOutChats(): List<Chat> {
        return chatRepository.findExpiredActiveFirstChats(
            now = OffsetDateTime.now()
        )
    }

    fun findTimedOutChatIds(
        now: OffsetDateTime,
        limit: Int
    ): List<UUID> {
        require(limit > 0) { "Timed-out chat candidate limit must be positive" }
        return chatRepository.findExpiredActiveFirstChatIds(
            now = now,
            pageable = PageRequest.of(0, limit)
        )
    }

    fun findTimedOutActiveSecondChats(): List<Chat> {
        return chatRepository.findTimedOutActiveSecondChats(
            now = OffsetDateTime.now()
        )
    }

    fun findTimedOutActiveSecondChatIds(
        now: OffsetDateTime,
        limit: Int
    ): List<UUID> {
        require(limit > 0) { "Timed-out active second-chat candidate limit must be positive" }
        return chatRepository.findTimedOutActiveSecondChatIds(
            now = now,
            pageable = PageRequest.of(0, limit)
        )
    }

    fun findTimedOutAvailableSecondChats(): List<Chat> {
        return chatRepository.findTimedOutAvailableSecondChats(
            now = OffsetDateTime.now()
        )
    }

    fun findTimedOutAvailableSecondChatIds(
        now: OffsetDateTime,
        limit: Int
    ): List<UUID> {
        require(limit > 0) { "Timed-out available second-chat candidate limit must be positive" }
        return chatRepository.findTimedOutAvailableSecondChatIds(
            now = now,
            pageable = PageRequest.of(0, limit)
        )
    }

    fun findExpiredReadOnlySecondChats(): List<Chat> {
        return chatRepository.findExpiredReadOnlySecondChats(
            now = OffsetDateTime.now()
        )
    }

    fun findExpiredReadOnlySecondChatIds(
        now: OffsetDateTime,
        limit: Int
    ): List<UUID> {
        require(limit > 0) { "Expired read-only second-chat candidate limit must be positive" }
        return chatRepository.findExpiredReadOnlySecondChatIds(
            now = now,
            pageable = PageRequest.of(0, limit)
        )
    }

    fun closeExpiredScheduledSecondChatWindow(
        connectionId: UUID,
        confirmedDateTime: OffsetDateTime
    ): Boolean {
        val connection = connectionService.findByIdOrThrow(connectionId)

        if (connection.state != ConnectionState.SECOND_CHAT_SCHEDULED) {
            return false
        }

        if (!isSecondChatWindowExpired(confirmedDateTime, OffsetDateTime.now())) {
            return false
        }

        val existingSecondChat =
            chatRepository.findByConnectionIdAndChatType(
                connectionId = connectionId,
                chatType = ChatType.SECOND_CHAT
            )

        if (existingSecondChat != null) {
            return false
        }

        connectionService.closeConnection(connectionId)
        return true
    }

    fun closeExpiredUnactivatedSecondChat(chatId: UUID): Boolean {
        val chat = findByIdOrThrow(chatId)

        if (chat.chatType != ChatType.SECOND_CHAT || chat.status != ChatStatus.AVAILABLE) {
            return false
        }

        if (chat.timeoutAt.isAfter(OffsetDateTime.now())) {
            return false
        }

        chat.status = ChatStatus.CLOSED
        chat.endedAt = OffsetDateTime.now()
        chat.endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
        chatRepository.save(chat)
        recordChatEnded(chat)

        chat.connectionId?.let { connectionService.closeConnection(it) }

        return true
    }

    fun expireSecondChatToReadOnly(chatId: UUID): Boolean {
        val chat = findByIdOrThrow(chatId)

        if (chat.chatType != ChatType.SECOND_CHAT || chat.status != ChatStatus.ACTIVE) {
            return false
        }

        val now = OffsetDateTime.now()
        if (chat.timeoutAt.isAfter(now)) {
            return false
        }

        chat.status = ChatStatus.EXPIRED
        chat.endedAt = now
        chat.endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
        chat.readOnlyUntil = now.plusMinutes(secondChatReadOnlyRetentionMinutes)
        chatRepository.save(chat)
        chat.connectionId?.let { connectionId ->
            val connection = connectionService.findByIdOrThrow(connectionId)
            homeStateInvalidationService.bumpBoth(
                userAId = connection.userAId,
                userBId = connection.userBId,
                reason = "second_chat_read_only"
            )
        }

        return true
    }

    fun closeExpiredReadOnlySecondChat(chatId: UUID): Boolean {
        val chat = findByIdOrThrow(chatId)

        if (
            chat.chatType != ChatType.SECOND_CHAT ||
            chat.status !in setOf(ChatStatus.FINISHED, ChatStatus.EXPIRED, ChatStatus.ABANDONED)
        ) {
            return false
        }

        val readOnlyUntil = chat.readOnlyUntil ?: return false
        if (readOnlyUntil.isAfter(OffsetDateTime.now())) {
            return false
        }

        chat.status = ChatStatus.CLOSED
        if (
            chat.endedReason !in setOf(
                ChatEndReason.SECOND_CHAT_NO_SHOW,
                ChatEndReason.SECOND_CHAT_MUTUAL_COMPLETION,
                ChatEndReason.SECOND_CHAT_PARTNER_INACTIVITY,
                ChatEndReason.SECOND_CHAT_NO_CONVERSATION_STARTED
            )
        ) {
            chat.endedReason = ChatEndReason.SECOND_CHAT_READ_ONLY_EXPIRED
        }
        chatRepository.save(chat)
        recordChatEnded(chat)

        chat.connectionId?.let { connectionService.closeConnection(it) }

        return true
    }

    private fun recordChatEnded(
        chat: Chat,
        actorUserId: UUID? = null
    ) {
        auditEventService.record(
            eventType = AuditEventType.CHAT_ENDED,
            aggregateType = AuditAggregateType.CHAT,
            aggregateId = chat.id,
            actorUserId = actorUserId,
            metadata = mapOf(
                "chatType" to chat.chatType.name,
                "status" to chat.status.name,
                "endedReason" to chat.endedReason?.name,
                "matchId" to chat.matchId,
                "connectionId" to chat.connectionId
            )
        )
    }

    private fun recordFirstChatExpirationReliability(chat: Chat) {
        val match = matchService.findByIdOrThrow(chat.matchId)
        val decision = chatDecisionRepository.findByChatId(chat.id)
        val unresolvedUserIds =
            buildList {
                if (decision?.userADecision == null) {
                    add(match.userAId)
                }
                if (decision?.userBDecision == null) {
                    add(match.userBId)
                }
            }

        unresolvedUserIds.forEach { userId ->
            userReliabilityScoreService.recordEvent(
                userId = userId,
                eventType = UserReliabilityEventType.FIRST_CHAT_EXPIRED_NO_DECISION,
                relatedMatchId = match.id,
                relatedChatId = chat.id
            )
        }
    }

    fun findActiveFirstChatOrThrow(matchId: UUID): Chat {
        return findActiveFirstChatOrThrow(
            matchId = matchId,
            unavailableCode = DomainErrorCode.CHAT_NOT_AVAILABLE
        )
    }

    private fun findActiveFirstChatOrThrow(
        matchId: UUID,
        unavailableCode: DomainErrorCode
    ): Chat {
        val chat =
            chatRepository.findByMatchIdAndChatType(matchId, ChatType.FIRST_CHAT)
                ?: throw chatNotFound()

        if (chat.status != ChatStatus.ACTIVE) {
            throw chatUnavailableForStatus(chat.status, unavailableCode)
        }

        validateActiveChatWindow(chat)

        return chat
    }

    fun findActiveFirstChatForUserOrThrow(
        matchId: UUID,
        userId: UUID
    ): Chat {
        val chat = findActiveFirstChatOrThrow(matchId)
        validateChatParticipant(chat, userId)
        return chat
    }

    fun findVisibleSecondChatOrThrow(
        connectionId: UUID,
        userId: UUID
    ): Chat {
        val connection = connectionService.findByIdForUserOrThrow(
            connectionId = connectionId,
            userId = userId
        )

        val chat =
            chatRepository.findByConnectionIdAndChatType(
                connectionId,
                ChatType.SECOND_CHAT
            )

        if (chat == null) {
            val negotiation = negotiationRepository.findByConnectionId(connectionId)
                ?: throw secondChatNotAvailable(
                    message = "Second chat is not scheduled for connection $connectionId"
                )
            val confirmedDateTime = negotiation.confirmedDateTime
            if (negotiation.status != NegotiationStatus.CONFIRMED || confirmedDateTime == null) {
                throw secondChatNotAvailable(
                    message = "Second chat is not confirmed for connection $connectionId"
                )
            }
            validateSecondChatEntryWindow(
                connectionId = connectionId,
                availableAt = confirmedDateTime,
                expiresAt = confirmedDateTime.plusMinutes(secondChatDurationMinutes),
                now = OffsetDateTime.now(),
                joinRequiredWhenOpen = true
            )
        }

        if (
            chat?.status == ChatStatus.ACTIVE ||
            chat?.status == ChatStatus.FINISHED ||
            chat?.status == ChatStatus.EXPIRED ||
            chat?.status == ChatStatus.ABANDONED
        ) {
            return chat
        }

        throw secondChatNotAvailable(
            message = "Second chat for connection $connectionId is not available " +
                "(chat status: ${chat?.status}, connection state: ${connection.state})"
        )
    }

    private fun materializeSecondChatForEntry(
        connection: Connection,
        connectionId: UUID
    ): Chat {
        if (
            connection.state != ConnectionState.SECOND_CHAT_SCHEDULED &&
            connection.state != ConnectionState.SECOND_CHAT_AVAILABLE
        ) {
            throw secondChatNotAvailable(
                message = "Second chat is not available while connection $connectionId is in state ${connection.state}"
            )
        }

        val negotiation =
            negotiationRepository.findByConnectionId(connectionId)
                ?: throw secondChatNotAvailable(
                    message = "Second chat is not scheduled for connection $connectionId"
                )

        if (negotiation.status != NegotiationStatus.CONFIRMED || negotiation.confirmedDateTime == null) {
            throw secondChatNotAvailable(
                message = "Second chat is not confirmed for connection $connectionId"
            )
        }

        val availableAt = negotiation.confirmedDateTime ?: error("checked above")
        val now = OffsetDateTime.now()
        validateSecondChatEntryWindow(
            connectionId = connectionId,
            availableAt = availableAt,
            expiresAt = availableAt.plusMinutes(secondChatDurationMinutes),
            now = now
        )

        startSecondChat(
            matchId = connection.matchId,
            connectionId = connectionId,
            availableAt = availableAt,
            activatedAt = now
        )

        transitionConnectionToSecondChat(connectionId)

        return chatRepository.findByConnectionIdAndChatType(connectionId, ChatType.SECOND_CHAT)
            ?: error("SECOND_CHAT was not created for connection $connectionId")
    }

    private fun activateAvailableSecondChatIfNeeded(
        chat: Chat,
        userId: UUID
    ): Chat {
        if (chat.status != ChatStatus.AVAILABLE) {
            return chat
        }

        if (chat.chatType != ChatType.SECOND_CHAT) {
            throw chatNotAvailable()
        }

        val connectionId = chat.connectionId ?: throw chatNotAvailable()
        val connection = connectionService.lockByIdOrThrow(connectionId)

        if (userId != connection.userAId && userId != connection.userBId) {
            throw AccessDeniedException("User $userId does not belong to connection $connectionId")
        }

        val currentChat = chatRepository.findById(chat.id).orElseThrow {
            NoSuchElementException("Chat not found: ${chat.id}")
        }
        if (currentChat.status != ChatStatus.AVAILABLE) {
            return currentChat
        }

        val now = OffsetDateTime.now()
        val availableAt = currentChat.availableAt ?: currentChat.startedAt
        validateSecondChatEntryWindow(
            connectionId = connectionId,
            availableAt = availableAt,
            expiresAt = currentChat.timeoutAt,
            now = now
        )

        chatRepository.activateAvailableSecondChat(
            chatId = currentChat.id,
            activatedAt = now
        )

        transitionConnectionToSecondChat(connectionId)

        return chatRepository.findById(currentChat.id).orElseThrow {
            NoSuchElementException("Chat not found: ${currentChat.id}")
        }
    }

    private fun findByIdForUpdateOrThrow(chatId: UUID): Chat =
        chatRepository.findByIdForUpdate(chatId)
            ?: throw chatNotFound()

    private fun findActiveFirstChatForUpdateOrThrow(matchId: UUID): Chat {
        val chat =
            chatRepository.findByMatchIdAndChatType(matchId, ChatType.FIRST_CHAT)
                ?: throw chatNotFound()

        val lockedChat = findByIdForUpdateOrThrow(chat.id)

        if (lockedChat.status != ChatStatus.ACTIVE) {
            throw chatUnavailableForStatus(
                status = lockedChat.status,
                fallbackCode = DomainErrorCode.CHAT_DECISION_NOT_AVAILABLE
            )
        }

        validateActiveChatWindow(lockedChat)

        return lockedChat
    }

    private fun requireNoPendingMutualCancellation(chatId: UUID) {
        if (
            chatExitRequestRepository.findByChatIdAndStatusAndType(
                chatId = chatId,
                status = ChatExitRequestStatus.PENDING,
                type = ChatExitRequestType.MUTUAL_CANCEL
            ) != null
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.CHAT_MUTUAL_CANCELLATION_PENDING,
                message = "A mutual cancellation request is pending"
            )
        }
    }

    private fun transitionConnectionToSecondChat(connectionId: UUID) {
        val connection = connectionService.findByIdOrThrow(connectionId)
        if (
            connection.state != ConnectionState.SECOND_CHAT_SCHEDULED &&
            connection.state != ConnectionState.SECOND_CHAT_AVAILABLE &&
            connection.state != ConnectionState.SECOND_CHAT
        ) {
            throw secondChatNotAvailable(
                message = "Second chat is not available while connection $connectionId is in state ${connection.state}"
            )
        }

        connectionService.transitionToSecondChatIdempotent(connectionId)
    }

    fun isSecondChatWindowExpired(
        availableAt: OffsetDateTime,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean =
        !availableAt.plusMinutes(secondChatDurationMinutes).isAfter(now)

    private fun validateSecondChatEntryWindow(
        connectionId: UUID,
        availableAt: OffsetDateTime,
        expiresAt: OffsetDateTime,
        now: OffsetDateTime,
        joinRequiredWhenOpen: Boolean = false
    ) {
        if (now.isBefore(availableAt)) {
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_NOT_AVAILABLE_YET,
                message = "Second chat for connection $connectionId is available at $availableAt"
            )
        }

        if (!expiresAt.isAfter(now)) {
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_EXPIRED,
                message = "Second chat for connection $connectionId expired at $expiresAt"
            )
        }

        if (joinRequiredWhenOpen) {
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_JOIN_REQUIRED,
                message = "Second chat for connection $connectionId requires explicit join"
            )
        }
    }

    private fun secondChatNotAvailable(
        message: String
    ): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.SECOND_CHAT_NOT_AVAILABLE,
            message = message
        )

    private fun validateActiveChatWindow(chat: Chat) {
        if (chat.status == ChatStatus.ABANDONED) {
            throw chatAbandoned()
        }

        if (chat.status == ChatStatus.EXPIRED) {
            throw chatExpired()
        }

        if (chat.status != ChatStatus.ACTIVE) {
            throw chatNotAvailable()
        }

        val now = OffsetDateTime.now()

        if (!now.isBefore(chat.timeoutAt)) {
            if (chat.chatType == ChatType.FIRST_CHAT) {
                endChat(
                    chatId = chat.id,
                    finalStatus = ChatStatus.EXPIRED,
                    endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
                )
            }
            throw chatExpired()
        }

        if (
            chat.chatType == ChatType.FIRST_CHAT &&
            inactivityExpiresAt(chat)?.isAfter(now) == false
        ) {
            endChat(
                chatId = chat.id,
                finalStatus = ChatStatus.ABANDONED,
                endedReason = ChatEndReason.INACTIVITY_TIMEOUT
            )
            throw chatAbandoned()
        }
    }

    private fun validateActiveChatWindowSideEffectFree(
        chat: Chat,
        now: OffsetDateTime
    ) {
        if (chat.status == ChatStatus.ABANDONED) {
            throw chatAbandoned()
        }

        if (chat.status == ChatStatus.EXPIRED) {
            throw chatExpired()
        }

        if (chat.status != ChatStatus.ACTIVE) {
            throw chatNotAvailable()
        }

        if (!now.isBefore(chat.timeoutAt)) {
            throw chatExpired()
        }

        if (
            chat.chatType == ChatType.FIRST_CHAT &&
            inactivityExpiresAt(chat)?.isAfter(now) == false
        ) {
            throw chatAbandoned()
        }
    }

    private fun validateChatReadable(chat: Chat) {
        if (chat.status == ChatStatus.CLOSED) {
            throw chatNotAvailable()
        }
    }

    private fun validateChatParticipant(
        chat: Chat,
        userId: UUID
    ) {
        val match = matchService.findByIdOrThrow(chat.matchId)

        if (userId != match.userAId && userId != match.userBId) {
            throw AccessDeniedException("User $userId does not belong to match ${chat.matchId}")
        }
    }

    private fun requireSecondChatJoinedForMessage(
        chat: Chat,
        senderId: UUID
    ) {
        if (chat.chatType != ChatType.SECOND_CHAT) {
            return
        }
        val connectionId = chat.connectionId ?: throw chatNotAvailable()
        val participation =
            secondChatParticipationRepository.findByConnectionIdAndUserId(
                connectionId = connectionId,
                userId = senderId
            )
        if (
            participation?.attendanceStatus != SecondChatAttendanceStatus.ON_TIME &&
            participation?.attendanceStatus != SecondChatAttendanceStatus.LATE
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_JOIN_REQUIRED,
                message = "Second chat for connection $connectionId requires explicit join before sending messages"
            )
        }
    }

    private fun requireChatPairNotBlocked(chat: Chat) {
        val pair = chat.connectionId?.let { connectionService.findByIdOrThrow(it) }
        if (pair != null) {
            userBlockService.requirePairNotBlocked(pair.userAId, pair.userBId)
        } else {
            val match = matchService.findByIdOrThrow(chat.matchId)
            userBlockService.requirePairNotBlocked(match.userAId, match.userBId)
        }
    }

    private fun normalizeMessageContent(content: String): String {
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        if (normalized.isBlank()) {
            throw invalidChatMessage()
        }

        if (normalized.length > MESSAGE_MAX_LENGTH) {
            throw invalidChatMessage()
        }

        if (
            normalized.any {
                (it.isISOControl() && it != '\n') ||
                        it == '<' ||
                        it == '>'
            }
        ) {
            throw invalidChatMessage()
        }

        return normalized
    }

    private fun chatNotFound(): DomainNotFoundException =
        DomainNotFoundException(
            code = DomainErrorCode.CHAT_NOT_FOUND,
            message = "Chat was not found"
        )

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

    private fun chatAbandoned(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_ABANDONED,
            message = "Chat was closed due to inactivity"
        )

    private fun chatUnavailableForStatus(
        status: ChatStatus,
        fallbackCode: DomainErrorCode
    ): DomainConflictException =
        when (status) {
            ChatStatus.ABANDONED -> chatAbandoned()
            ChatStatus.EXPIRED -> chatExpired()
            else -> DomainConflictException(
                code = fallbackCode,
                message = "Chat is not available"
            )
        }

    private fun invalidChatMessage(): DomainBadRequestException =
        DomainBadRequestException(
            code = DomainErrorCode.CHAT_MESSAGE_INVALID,
            message = "Chat message is invalid"
        )

    private fun resolveMessagePageLimit(limit: Int?): Int {
        val resolvedLimit = limit ?: defaultMessagePageLimit

        require(resolvedLimit in 1..MESSAGE_PAGE_LIMIT_MAX) {
            "Message limit must be between 1 and $MESSAGE_PAGE_LIMIT_MAX"
        }

        return resolvedLimit
    }

    private fun chatDecisionNotAvailable(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_DECISION_NOT_AVAILABLE,
            message = "Chat decision is not available"
        )

    private fun chatDecisionAlreadySubmitted(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_DECISION_ALREADY_SUBMITTED,
            message = "Chat decision was already submitted"
        )

    private fun resolveParticipantDecisionStatus(
        chat: Chat,
        userId: UUID,
        chatDecisionValue: ChatContinueDecision?
    ): ChatParticipantDecisionStatus {
        if (chat.status == ChatStatus.ABANDONED) {
            return ChatParticipantDecisionStatus.ABANDONED
        }

        val terminalExit =
            chatExitService.findExitRequests(
                chatId = chat.id,
                userId = userId
            ).firstOrNull {
                it.status == ChatExitRequestStatus.ACCEPTED &&
                    (it.type == ChatExitRequestType.UNILATERAL_CANCEL ||
                        it.type == ChatExitRequestType.SAFETY_REPORT) &&
                    it.requesterUserId == userId
            }

        if (terminalExit != null) {
            return ChatParticipantDecisionStatus.REJECTED
        }

        return when (chatDecisionValue) {
            ChatContinueDecision.APPROVED -> ChatParticipantDecisionStatus.APPROVED
            ChatContinueDecision.REJECTED -> ChatParticipantDecisionStatus.REJECTED
            null -> ChatParticipantDecisionStatus.PENDING
        }
    }
}
