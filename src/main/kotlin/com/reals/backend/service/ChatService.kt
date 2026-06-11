package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatDecision
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatExitRequestStatus
import com.reals.backend.domain.ChatExitRequestType
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatParticipantDecisionStatus
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.MatchState
import com.reals.backend.repository.ChatDecisionRepository
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
class ChatService(
    private val chatRepository: ChatRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val chatDecisionRepository: ChatDecisionRepository,
    private val matchService: MatchService,
    private val visualReviewService: VisualReviewService,
    private val penaltyService: PenaltyService,
    private val connectionService: ConnectionService,
    private val chatExitService: ChatExitService,

    @param:Value("\${chat.first-chat.duration-minutes:1440}")
    private val firstChatDurationMinutes: Long,

    @param:Value("\${chat.second-chat.duration-minutes:2880}")
    private val secondChatDurationMinutes: Long,

    @param:Value("\${chat.first-chat.min-messages-per-user:0}")
    private val minMessagesPerUser: Int
) {

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
                NoSuchElementException("Chat not found: $chatId")
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

        return chatRepository.save(
            Chat(
                matchId = matchId,
                chatType = ChatType.FIRST_CHAT,
                startedAt = now,
                timeoutAt = now.plusMinutes(firstChatDurationMinutes)
            )
        )
    }

    fun startSecondChat(
        matchId: UUID,
        connectionId: UUID,
        availableAt: OffsetDateTime = OffsetDateTime.now()
    ): Chat {
        chatRepository
            .findByConnectionIdAndChatType(connectionId, ChatType.SECOND_CHAT)
            ?.let { return it }

        return chatRepository.save(
            Chat(
                matchId = matchId,
                connectionId = connectionId,
                chatType = ChatType.SECOND_CHAT,
                status = ChatStatus.AVAILABLE,
                startedAt = availableAt,
                availableAt = availableAt,
                timeoutAt = availableAt.plusMinutes(secondChatDurationMinutes)
            )
        )
    }

    fun makeSecondChatAvailable(
        matchId: UUID,
        connectionId: UUID,
        availableAt: OffsetDateTime
    ): Chat {
        val chat =
            startSecondChat(
                matchId = matchId,
                connectionId = connectionId,
                availableAt = availableAt
            )

        connectionService.transitionToSecondChatAvailable(connectionId)

        return chat
    }

    fun sendMessage(
        chatId: UUID,
        senderId: UUID,
        content: String
    ): ChatMessage {
        val normalizedContent = normalizeMessageContent(content)

        val chat =
            activateAvailableSecondChatIfNeeded(
                chat = findByIdOrThrow(chatId),
                userId = senderId
            )

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

        chat.lastMessageAt = message.sentAt
        chatRepository.save(chat)

        return message
    }

    fun recordChatDecision(
        matchId: UUID,
        userId: UUID,
        decision: ChatContinueDecision
    ) {
        val match = matchService.findByIdOrThrow(matchId)

        check(match.state == MatchState.CHAT_ACTIVE) {
            "Match $matchId is not in CHAT_ACTIVE state (current: ${match.state})"
        }

        val chat = findActiveFirstChatOrThrow(matchId)

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

            check(sent >= minMessagesPerUser) {
                "Cannot approve: user has sent $sent message(s), minimum required is $minMessagesPerUser"
            }
        }

        when (userId) {
            match.userAId -> {
                check(chatDecision.userADecision == null) {
                    "User A already submitted a chat decision for match $matchId"
                }
                chatDecision.userADecision = decision
            }

            match.userBId -> {
                check(chatDecision.userBDecision == null) {
                    "User B already submitted a chat decision for match $matchId"
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
            chatRepository.save(chat)

            matchService.transitionToVisualPhase(matchId)
            visualReviewService.initializeForMatch(matchId)
        }
    }

    fun endChat(
        chatId: UUID,
        finalStatus: ChatStatus,
        abandonedUserIds: List<UUID> = emptyList()
    ): Boolean {
        require(finalStatus == ChatStatus.EXPIRED || finalStatus == ChatStatus.ABANDONED) {
            "endChat only accepts EXPIRED or ABANDONED, got $finalStatus"
        }

        val chat = findByIdOrThrow(chatId)

        if (chat.status != ChatStatus.ACTIVE) return false

        chat.status = finalStatus
        chat.endedAt = OffsetDateTime.now()
        chatRepository.save(chat)

        when (chat.chatType) {
            ChatType.FIRST_CHAT -> matchService.expireMatch(chat.matchId)

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
        userId: UUID
    ): List<ChatMessage> {
        val chat = findByIdOrThrow(chatId)
        validateChatParticipant(chat, userId)
        return chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(chatId)
    }

    fun getMessagesAfter(
        chatId: UUID,
        userId: UUID,
        afterMessageId: UUID
    ): List<ChatMessage> {
        val chat = findByIdOrThrow(chatId)
        validateChatParticipant(chat, userId)

        val afterMessage =
            chatMessageRepository.findById(afterMessageId)
                .orElseThrow {
                    NoSuchElementException("Chat message not found: $afterMessageId")
                }

        check(afterMessage.chatSessionId == chatId) {
            "Message $afterMessageId does not belong to chat $chatId"
        }

        return chatMessageRepository.findByChatSessionIdAndSentAtGreaterThanEqualOrderBySentAtAscIdAsc(
            chatSessionId = chatId,
            sentAt = afterMessage.sentAt
        ).dropWhile { it.id != afterMessageId }
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
        return chatRepository.findExpiredActiveChats(
            now = OffsetDateTime.now()
        )
    }

    fun findActiveFirstChatOrThrow(matchId: UUID): Chat {
        val chat =
            chatRepository.findByMatchIdAndChatType(matchId, ChatType.FIRST_CHAT)
                ?: throw NoSuchElementException("No FIRST_CHAT found for match: $matchId")

        check(chat.status == ChatStatus.ACTIVE) {
            "Chat for match $matchId is not active (status: ${chat.status})"
        }

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
        val chat =
            chatRepository.findByConnectionIdAndChatType(
                connectionId,
                ChatType.SECOND_CHAT
            )
                ?: throw NoSuchElementException("No SECOND_CHAT found for connection: $connectionId")

        val connection = connectionService.findByIdForUserOrThrow(
            connectionId = connectionId,
            userId = userId
        )

        val visibleChat = activateAvailableSecondChatIfNeeded(
            chat = chat,
            userId = userId
        )

        check(visibleChat.status == ChatStatus.ACTIVE) {
            "Second chat for connection $connectionId is not active " +
                "(chat status: ${visibleChat.status}, connection state: ${connection.state})"
        }

        return visibleChat
    }

    private fun activateAvailableSecondChatIfNeeded(
        chat: Chat,
        userId: UUID
    ): Chat {
        if (chat.status != ChatStatus.AVAILABLE) {
            return chat
        }

        check(chat.chatType == ChatType.SECOND_CHAT) {
            "Only SECOND_CHAT can be activated from AVAILABLE"
        }

        val connectionId = checkNotNull(chat.connectionId) {
            "SECOND_CHAT has no connectionId"
        }
        val connection = connectionService.findByIdOrThrow(connectionId)

        if (userId != connection.userAId && userId != connection.userBId) {
            throw AccessDeniedException("User $userId does not belong to connection $connectionId")
        }

        val now = OffsetDateTime.now()
        chat.status = ChatStatus.ACTIVE
        chat.startedAt = now
        chat.activatedAt = now
        chat.timeoutAt = now.plusMinutes(secondChatDurationMinutes)

        connectionService.transitionToSecondChat(connectionId)

        return chatRepository.save(chat)
    }

    private fun validateActiveChatWindow(chat: Chat) {
        check(chat.status == ChatStatus.ACTIVE) {
            "Chat ${chat.id} is not active (status: ${chat.status})"
        }

        check(OffsetDateTime.now().isBefore(chat.timeoutAt)) {
            "Chat ${chat.id} has timed out"
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

    private fun normalizeMessageContent(content: String): String {
        val normalized = content.trim()

        require(normalized.isNotBlank()) {
            "Message content is required"
        }

        require(normalized.length <= MESSAGE_MAX_LENGTH) {
            "Message content must be at most $MESSAGE_MAX_LENGTH characters"
        }

        PlainText.requireValid("Message content", normalized)

        return normalized
    }

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
