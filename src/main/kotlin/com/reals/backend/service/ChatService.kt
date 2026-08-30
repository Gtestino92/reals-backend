package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageReactionType
import com.reals.backend.domain.ChatParticipantDecisionStatus
import com.reals.backend.domain.ChatReplyTargetType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.service.affinity.AffinityDerivedSnapshotInitializationService
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
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
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val chatAccessService: ChatAccessService,
    private val chatLifecycleService: ChatLifecycleService,
    private val chatMessageService: ChatMessageService,
    private val firstChatResolutionService: FirstChatResolutionService,
    private val matchService: MatchService,
    private val connectionService: ConnectionService,
    private val firstChatDecisionPolicyService: FirstChatDecisionPolicyService,
    private val firstChatGuidanceService: FirstChatGuidanceService,
    private val affinityDerivedSnapshotInitializationService: AffinityDerivedSnapshotInitializationService,
    private val homeStateInvalidationService: HomeStateInvalidationService,
    private val userBlockService: UserBlockService,

    @param:Value("\${chat.first-chat.duration-minutes:15}")
    private val firstChatDurationMinutes: Long,

    @param:Value("\${chat.second-chat.duration-minutes:2880}")
    private val secondChatDurationMinutes: Long
) {

    sealed interface SendMessageResult {
        data class Sent(val message: ChatMessage) : SendMessageResult
        data class RejectedAfterResolution(
            val code: DomainErrorCode,
            val message: String
        ) : SendMessageResult
    }

    data class ChatReplyTarget(
        val type: ChatReplyTargetType,
        val targetId: UUID
    )

    sealed interface SendAudioMessageResult {
        data class Created(val message: ChatMessage) : SendAudioMessageResult
        data class Replayed(val message: ChatMessage) : SendAudioMessageResult
        data class RejectedAfterResolution(
            val code: DomainErrorCode,
            val message: String
        ) : SendAudioMessageResult
    }

    data class ParticipantDecisionStatuses(
        val myDecision: ChatParticipantDecisionStatus,
        val partnerDecision: ChatParticipantDecisionStatus
    )

    data class ChatMessagesPage(
        val messages: List<ChatMessage>,
        val hasMore: Boolean
    )

    fun findByIdOrThrow(chatId: UUID): Chat =
        chatAccessService.findByIdOrThrow(chatId)

    fun findByIdForUserOrThrow(
        chatId: UUID,
        userId: UUID
    ): Chat =
        chatAccessService.findByIdForUserOrThrow(chatId = chatId, userId = userId)

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
        affinityDerivedSnapshotInitializationService.initializeForFirstChat(
            chat = chat,
            match = match,
            now = now
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

    fun inactivityExpiresAt(chat: Chat): OffsetDateTime? =
        chatLifecycleService.inactivityExpiresAt(chat)

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
        content: String,
        clientMessageId: UUID? = null,
        replyTarget: ChatReplyTarget? = null
    ): ChatMessage =
        when (val result = sendMessageWithResult(chatId, senderId, content, clientMessageId, replyTarget)) {
            is SendMessageResult.Sent -> result.message
            is SendMessageResult.RejectedAfterResolution ->
                throw DomainConflictException(code = result.code, message = result.message)
        }

    fun sendMessageWithResult(
        chatId: UUID,
        senderId: UUID,
        content: String,
        clientMessageId: UUID? = null,
        replyTarget: ChatReplyTarget? = null,
        now: OffsetDateTime? = null
    ): SendMessageResult =
        when (
            val result = chatMessageService.sendMessageWithResult(
                chatId = chatId,
                senderId = senderId,
                content = content,
                clientMessageId = clientMessageId,
                replyTarget = replyTarget.toMessageTarget(),
                now = now
            )
        ) {
            is ChatMessageService.SendMessageResult.Sent ->
                SendMessageResult.Sent(result.message)

            is ChatMessageService.SendMessageResult.RejectedAfterResolution ->
                SendMessageResult.RejectedAfterResolution(
                    code = result.code,
                    message = result.message
                )
        }

    fun findAudioMessageReplayOrThrowOnConflict(
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID,
        audioSha256: String,
        replyTarget: ChatReplyTarget? = null,
    ): ChatMessage? =
        chatMessageService.findAudioMessageReplayOrThrowOnConflict(
            chatId = chatId,
            senderId = senderId,
            clientMessageId = clientMessageId,
            audioSha256 = audioSha256,
            replyTarget = replyTarget.toMessageTarget()
        )

    fun preflightNewAudioMessage(
        chatId: UUID,
        senderId: UUID,
        now: OffsetDateTime = OffsetDateTime.now(),
        replyTarget: ChatReplyTarget? = null,
    ) {
        chatMessageService.preflightNewAudioMessage(
            chatId = chatId,
            senderId = senderId,
            now = now,
            replyTarget = replyTarget.toMessageTarget()
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
        now: OffsetDateTime = OffsetDateTime.now(),
        replyTarget: ChatReplyTarget? = null,
    ): SendAudioMessageResult =
        when (
            val result = chatMessageService.sendAudioMessageWithResult(
                chatId = chatId,
                senderId = senderId,
                clientMessageId = clientMessageId,
                audioContentType = audioContentType,
                audioSizeBytes = audioSizeBytes,
                audioDurationMillis = audioDurationMillis,
                audioSha256 = audioSha256,
                audioBucket = audioBucket,
                audioObjectKey = audioObjectKey,
                cleanupTaskId = cleanupTaskId,
                messageId = messageId,
                now = now,
                replyTarget = replyTarget.toMessageTarget()
            )
        ) {
            is ChatMessageService.SendAudioMessageResult.Created ->
                SendAudioMessageResult.Created(result.message)

            is ChatMessageService.SendAudioMessageResult.Replayed ->
                SendAudioMessageResult.Replayed(result.message)

            is ChatMessageService.SendAudioMessageResult.RejectedAfterResolution ->
                SendAudioMessageResult.RejectedAfterResolution(
                    code = result.code,
                    message = result.message
                )
        }

    fun putMessageReaction(
        chatId: UUID,
        messageId: UUID,
        userId: UUID,
        reactionType: ChatMessageReactionType,
        now: OffsetDateTime = OffsetDateTime.now()
    ): ChatMessage =
        chatMessageService.putMessageReaction(
            chatId = chatId,
            messageId = messageId,
            userId = userId,
            reactionType = reactionType,
            now = now
        )

    fun recordChatDecision(
        matchId: UUID,
        userId: UUID,
        decision: ChatContinueDecision
    ) {
        firstChatResolutionService.recordChatDecision(
            matchId = matchId,
            userId = userId,
            decision = decision
        )
    }

    fun endChat(
        chatId: UUID,
        finalStatus: ChatStatus,
        endedReason: ChatEndReason,
        abandonedUserIds: List<UUID> = emptyList()
    ): Boolean =
        chatLifecycleService.endChat(
            chatId = chatId,
            finalStatus = finalStatus,
            endedReason = endedReason,
            abandonedUserIds = abandonedUserIds
        )

    fun getMessages(
        chatId: UUID,
        userId: UUID,
        limit: Int? = null
    ): List<ChatMessage> =
        chatMessageService.getMessages(
            chatId = chatId,
            userId = userId,
            limit = limit
        )

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
        val chat = chatAccessService.findByIdForUpdateOrThrow(chatId)
        chatAccessService.requireChatPairNotBlocked(chat)
        chatAccessService.validateChatParticipant(chat, userId)

        if (chat.chatType != ChatType.FIRST_CHAT) {
            throw chatLifecycleService.chatNotAvailable()
        }

        chatLifecycleService.validateActiveChatWindow(chat)
        firstChatDecisionPolicyService.requireOrdinaryFirstChatMutationAllowed(chat, userId)
        chatLifecycleService.requireNoPendingMutualCancellation(chat.id)

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
    ): ChatMessagesPage {
        val page = chatMessageService.getMessagesAfter(
            chatId = chatId,
            userId = userId,
            afterMessageId = afterMessageId,
            limit = limit
        )
        return ChatMessagesPage(
            messages = page.messages,
            hasMore = page.hasMore
        )
    }

    fun getFirstChatDecisionStatuses(
        matchId: UUID,
        userId: UUID
    ): ParticipantDecisionStatuses {
        val statuses = firstChatResolutionService.getFirstChatDecisionStatuses(
            matchId = matchId,
            userId = userId
        )
        return ParticipantDecisionStatuses(
            myDecision = statuses.myDecision,
            partnerDecision = statuses.partnerDecision
        )
    }

    fun findInactiveChats(inactivityMinutes: Long): List<Chat> =
        chatLifecycleService.findInactiveChats(inactivityMinutes)

    fun findInactiveChatIds(
        threshold: OffsetDateTime,
        limit: Int
    ): List<UUID> =
        chatLifecycleService.findInactiveChatIds(threshold = threshold, limit = limit)

    fun findTimedOutChats(): List<Chat> =
        chatLifecycleService.findTimedOutChats()

    fun findTimedOutChatIds(
        now: OffsetDateTime,
        limit: Int
    ): List<UUID> =
        chatLifecycleService.findTimedOutChatIds(now = now, limit = limit)

    fun findTimedOutActiveSecondChats(): List<Chat> =
        chatLifecycleService.findTimedOutActiveSecondChats()

    fun findTimedOutActiveSecondChatIds(
        now: OffsetDateTime,
        limit: Int
    ): List<UUID> =
        chatLifecycleService.findTimedOutActiveSecondChatIds(now = now, limit = limit)

    fun findTimedOutAvailableSecondChats(): List<Chat> =
        chatLifecycleService.findTimedOutAvailableSecondChats()

    fun findTimedOutAvailableSecondChatIds(
        now: OffsetDateTime,
        limit: Int
    ): List<UUID> =
        chatLifecycleService.findTimedOutAvailableSecondChatIds(now = now, limit = limit)

    fun findExpiredReadOnlySecondChats(): List<Chat> =
        chatLifecycleService.findExpiredReadOnlySecondChats()

    fun findExpiredReadOnlySecondChatIds(
        now: OffsetDateTime,
        limit: Int
    ): List<UUID> =
        chatLifecycleService.findExpiredReadOnlySecondChatIds(now = now, limit = limit)

    fun closeExpiredScheduledSecondChatWindow(
        connectionId: UUID,
        confirmedDateTime: OffsetDateTime
    ): Boolean =
        chatLifecycleService.closeExpiredScheduledSecondChatWindow(
            connectionId = connectionId,
            confirmedDateTime = confirmedDateTime
        )

    fun closeExpiredUnactivatedSecondChat(chatId: UUID): Boolean =
        chatLifecycleService.closeExpiredUnactivatedSecondChat(chatId)

    fun expireSecondChatToReadOnly(chatId: UUID): Boolean =
        chatLifecycleService.expireSecondChatToReadOnly(chatId)

    fun closeExpiredReadOnlySecondChat(chatId: UUID): Boolean =
        chatLifecycleService.closeExpiredReadOnlySecondChat(chatId)

    fun findActiveFirstChatOrThrow(matchId: UUID): Chat =
        chatLifecycleService.findActiveFirstChatOrThrow(matchId)

    fun findActiveFirstChatForUserOrThrow(
        matchId: UUID,
        userId: UUID
    ): Chat {
        val chat = chatLifecycleService.findActiveFirstChatOrThrow(matchId)
        chatAccessService.validateChatParticipant(chat, userId)
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
                ?: throw chatLifecycleService.secondChatNotAvailable(
                    message = "Second chat is not scheduled for connection $connectionId"
                )
            val confirmedDateTime = negotiation.confirmedDateTime
            if (negotiation.status != NegotiationStatus.CONFIRMED || confirmedDateTime == null) {
                throw chatLifecycleService.secondChatNotAvailable(
                    message = "Second chat is not confirmed for connection $connectionId"
                )
            }
            chatLifecycleService.validateSecondChatEntryWindow(
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

        throw chatLifecycleService.secondChatNotAvailable(
            message = "Second chat for connection $connectionId is not available " +
                "(chat status: ${chat?.status}, connection state: ${connection.state})"
        )
    }

    fun isSecondChatWindowExpired(
        availableAt: OffsetDateTime,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean =
        chatLifecycleService.isSecondChatWindowExpired(availableAt = availableAt, now = now)

    private fun ChatReplyTarget?.toMessageTarget(): ChatMessageService.ChatReplyTarget? =
        this?.let {
            ChatMessageService.ChatReplyTarget(
                type = it.type,
                targetId = it.targetId
            )
        }

}
