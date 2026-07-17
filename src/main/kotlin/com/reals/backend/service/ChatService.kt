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
import com.reals.backend.domain.ChatParticipantDecisionStatus
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.repository.ChatDecisionRepository
import com.reals.backend.repository.ChatExitRequestRepository
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.service.reliability.UserReliabilityScoreService
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
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

    @param:Value("\${user-reliability.second-chat.no-show-grace-minutes:10}")
    private val secondChatNoShowGraceMinutes: Long
) {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    private companion object {
        const val MESSAGE_MAX_LENGTH = 1000
    }

    data class ParticipantDecisionStatuses(
        val myDecision: ChatParticipantDecisionStatus,
        val partnerDecision: ChatParticipantDecisionStatus
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
    ): ChatMessage {
        val normalizedContent = normalizeMessageContent(content)

        val initialStatus = chatRepository.findStatusById(chatId)
            ?: throw chatNotFound()
        if (initialStatus == ChatStatus.AVAILABLE) {
            val initialChat = findByIdOrThrow(chatId)
            val activatedChat = activateAvailableSecondChatIfNeeded(
                chat = initialChat,
                userId = senderId
            )
            entityManager.detach(initialChat)
            if (activatedChat !== initialChat) {
                entityManager.detach(activatedChat)
            }
        }
        val chat = findByIdForUpdateOrThrow(chatId)

        requireChatPairNotBlocked(chat)
        validateActiveChatWindow(chat)
        validateChatParticipant(chat, senderId)

        val message =
            chatMessageRepository.save(
                ChatMessage(
                    chatSessionId = chat.id,
                    senderId = senderId,
                    content = normalizedContent
                )
            )

        chat.lastMessageAt = maxOf(chat.lastMessageAt ?: message.sentAt, message.sentAt)
        chatRepository.save(chat)

        recordSecondChatAttendanceIfWithinGrace(chat, message)

        return message
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

        val chat = findActiveFirstChatOrThrow(
            matchId = matchId,
            unavailableCode = DomainErrorCode.CHAT_DECISION_NOT_AVAILABLE
        )

        if (
            chatExitRequestRepository.findByChatIdAndStatusAndType(
                chatId = chat.id,
                status = ChatExitRequestStatus.PENDING,
                type = ChatExitRequestType.MUTUAL_CANCEL
            ) != null
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.CHAT_MUTUAL_CANCELLATION_PENDING,
                message = "A mutual cancellation request is pending"
            )
        }

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

    fun evaluateSecondChatNoShows(now: OffsetDateTime = OffsetDateTime.now()): Int {
        val dueNegotiations =
            negotiationRepository.findConfirmedSecondChatNoShowDue(
                dueBefore = now.minusMinutes(secondChatNoShowGraceMinutes)
            )

        var recorded = 0

        dueNegotiations.forEach { negotiation ->
            val connection = connectionService.findByIdOrThrow(negotiation.connectionId)
            val confirmedDateTime = negotiation.confirmedDateTime ?: return@forEach
            val graceEndsAt = confirmedDateTime.plusMinutes(secondChatNoShowGraceMinutes)
            val secondChat =
                chatRepository.findByConnectionIdAndChatType(
                    connectionId = connection.id,
                    chatType = ChatType.SECOND_CHAT
                )

            listOf(connection.userAId, connection.userBId).forEach { participantId ->
                val sentWithinGrace =
                    secondChat != null &&
                        chatMessageRepository.countByChatSessionIdAndSenderIdAndSentAtLessThanEqual(
                            chatSessionId = secondChat.id,
                            senderId = participantId,
                            sentAt = graceEndsAt
                        ) > 0

                val eventType =
                    if (sentWithinGrace) {
                        UserReliabilityEventType.SECOND_CHAT_CONFIRMED_ATTENDED
                    } else {
                        UserReliabilityEventType.SECOND_CHAT_NO_SHOW
                    }

                val event =
                    userReliabilityScoreService.recordEvent(
                        userId = participantId,
                        eventType = eventType,
                        relatedMatchId = connection.matchId,
                        relatedConnectionId = connection.id,
                        relatedChatId = secondChat?.id
                    )
                if (event != null) {
                    recorded += 1
                }
            }
        }

        return recorded
    }

    fun getMessages(
        chatId: UUID,
        userId: UUID
    ): List<ChatMessage> {
        val chat = findByIdOrThrow(chatId)
        validateChatParticipant(chat, userId)
        validateChatReadable(chat)
        return chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(chatId)
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
        val chat = findByIdOrThrow(chatId)
        requireChatPairNotBlocked(chat)
        validateChatParticipant(chat, userId)

        if (chat.chatType != ChatType.FIRST_CHAT) {
            throw chatNotAvailable()
        }

        validateActiveChatWindow(chat)

        return firstChatGuidanceService.requestNext(
            chat = chat,
            userId = userId
        )
    }

    fun getMessagesAfter(
        chatId: UUID,
        userId: UUID,
        afterMessageId: UUID
    ): List<ChatMessage> {
        val chat = findByIdOrThrow(chatId)
        validateChatParticipant(chat, userId)
        validateChatReadable(chat)

        val afterMessage =
            chatMessageRepository.findById(afterMessageId)
                .orElseThrow {
                    chatNotAvailable()
                }

        if (afterMessage.chatSessionId != chatId) {
            throw chatNotAvailable()
        }

        return chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(chatId)
            .dropWhile { it.id != afterMessageId }
            .drop(1)
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

    fun findTimedOutChats(): List<Chat> {
        return chatRepository.findExpiredActiveFirstChats(
            now = OffsetDateTime.now()
        )
    }

    fun findTimedOutActiveSecondChats(): List<Chat> {
        return chatRepository.findTimedOutActiveSecondChats(
            now = OffsetDateTime.now()
        )
    }

    fun findTimedOutAvailableSecondChats(): List<Chat> {
        return chatRepository.findTimedOutAvailableSecondChats(
            now = OffsetDateTime.now()
        )
    }

    fun findExpiredReadOnlySecondChats(): List<Chat> {
        return chatRepository.findExpiredReadOnlySecondChats(
            now = OffsetDateTime.now()
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

        if (chat.chatType != ChatType.SECOND_CHAT || chat.status != ChatStatus.EXPIRED) {
            return false
        }

        val readOnlyUntil = chat.readOnlyUntil ?: return false
        if (readOnlyUntil.isAfter(OffsetDateTime.now())) {
            return false
        }

        chat.status = ChatStatus.CLOSED
        chat.endedReason = ChatEndReason.SECOND_CHAT_READ_ONLY_EXPIRED
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

    private fun recordSecondChatAttendanceIfWithinGrace(
        chat: Chat,
        message: ChatMessage
    ) {
        if (chat.chatType != ChatType.SECOND_CHAT) {
            return
        }

        val connectionId = chat.connectionId ?: return
        val availableAt = chat.availableAt ?: return
        if (message.sentAt.isAfter(availableAt.plusMinutes(secondChatNoShowGraceMinutes))) {
            return
        }

        userReliabilityScoreService.recordEvent(
            userId = message.senderId,
            eventType = UserReliabilityEventType.SECOND_CHAT_CONFIRMED_ATTENDED,
            relatedMatchId = chat.matchId,
            relatedConnectionId = connectionId,
            relatedChatId = chat.id
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
                ?: materializeSecondChatForEntry(connection, connectionId)

        val visibleChat = activateAvailableSecondChatIfNeeded(
            chat = chat,
            userId = userId
        )

        if (visibleChat.status == ChatStatus.ACTIVE || visibleChat.status == ChatStatus.EXPIRED) {
            return visibleChat
        }

        throw secondChatNotAvailable(
            message = "Second chat for connection $connectionId is not available " +
                "(chat status: ${visibleChat.status}, connection state: ${connection.state})"
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
        now: OffsetDateTime
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
