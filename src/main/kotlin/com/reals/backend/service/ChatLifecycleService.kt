package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatExitRequestStatus
import com.reals.backend.domain.ChatExitRequestType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.repository.ChatDecisionRepository
import com.reals.backend.repository.ChatExitRequestRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.service.reliability.UserReliabilityScoreService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class ChatLifecycleService(
    private val chatRepository: ChatRepository,
    private val chatDecisionRepository: ChatDecisionRepository,
    private val chatExitRequestRepository: ChatExitRequestRepository,
    private val matchService: MatchService,
    private val penaltyService: PenaltyService,
    private val connectionService: ConnectionService,
    private val firstChatDecisionPolicyService: FirstChatDecisionPolicyService,
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService,
    private val userReliabilityScoreService: UserReliabilityScoreService,
    private val eventPublisher: ApplicationEventPublisher,

    @param:Value("\${chat.second-chat.duration-minutes:2880}")
    private val secondChatDurationMinutes: Long,

    @param:Value("\${chat.second-chat.read-only-retention-minutes:1440}")
    private val secondChatReadOnlyRetentionMinutes: Long,

    @param:Value("\${chat.first-chat.inactivity-threshold-minutes:5}")
    private val firstChatInactivityThresholdMinutes: Long
) {

    fun inactivityExpiresAt(chat: Chat): OffsetDateTime? {
        if (chat.chatType != ChatType.FIRST_CHAT) {
            return null
        }

        if (firstChatDecisionPolicyService.isDecisionOnly(chat)) {
            return null
        }

        return (chat.lastMessageAt ?: chat.startedAt)
            .plusMinutes(firstChatInactivityThresholdMinutes)
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

        val chat = findByIdForUpdateOrThrow(chatId)

        if (chat.status != ChatStatus.ACTIVE) return false

        if (
            finalStatus == ChatStatus.ABANDONED &&
            endedReason == ChatEndReason.INACTIVITY_TIMEOUT &&
            firstChatDecisionPolicyService.isDecisionOnly(chat)
        ) {
            return false
        }

        chat.status = finalStatus
        chat.endedAt = OffsetDateTime.now()
        chat.endedReason = endedReason
        chatRepository.save(chat)
        recordChatEnded(chat)

        when (chat.chatType) {
            ChatType.FIRST_CHAT -> {
                recordFirstChatExpirationReliability(chat)
                publishFirstChatTerminated(chat)
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

    fun recordChatEnded(
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

    fun publishFirstChatTerminated(chat: Chat) {
        val endedReason = chat.endedReason ?: return
        eventPublisher.publishEvent(
            FirstChatTerminatedEvent(
                matchId = chat.matchId,
                chatId = chat.id,
                finalStatus = chat.status,
                endedReason = endedReason
            )
        )
    }

    fun findActiveFirstChatOrThrow(matchId: UUID): Chat {
        return findActiveFirstChatOrThrow(
            matchId = matchId,
            unavailableCode = DomainErrorCode.CHAT_NOT_AVAILABLE
        )
    }

    fun findActiveFirstChatOrThrow(
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

    fun findActiveFirstChatForUpdateOrThrow(matchId: UUID): Chat {
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

    fun requireNoPendingMutualCancellation(chatId: UUID) {
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

    fun isSecondChatWindowExpired(
        availableAt: OffsetDateTime,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean =
        !availableAt.plusMinutes(secondChatDurationMinutes).isAfter(now)

    fun validateSecondChatEntryWindow(
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

    fun validateActiveChatWindow(chat: Chat) {
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

    fun validateActiveChatWindowSideEffectFree(
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

    fun validateChatReadable(chat: Chat) {
        if (chat.status == ChatStatus.CLOSED) {
            throw chatNotAvailable()
        }
    }

    fun secondChatNotAvailable(
        message: String
    ): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.SECOND_CHAT_NOT_AVAILABLE,
            message = message
        )

    fun chatNotFound(): DomainNotFoundException =
        DomainNotFoundException(
            code = DomainErrorCode.CHAT_NOT_FOUND,
            message = "Chat was not found"
        )

    fun chatNotAvailable(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_NOT_AVAILABLE,
            message = "Chat is not available"
        )

    fun chatExpired(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_EXPIRED,
            message = "Chat has expired"
        )

    fun chatAbandoned(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_ABANDONED,
            message = "Chat was closed due to inactivity"
        )

    fun chatUnavailableForStatus(
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

    private fun findByIdOrThrow(chatId: UUID): Chat {
        return chatRepository.findById(chatId)
            .orElseThrow {
                chatNotFound()
            }
    }

    private fun findByIdForUpdateOrThrow(chatId: UUID): Chat =
        chatRepository.findByIdForUpdate(chatId)
            ?: throw chatNotFound()

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
}
