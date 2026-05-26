package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatDecision
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.MatchState
import com.reals.backend.repository.ChatDecisionRepository
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
class ChatService(
    private val chatRepository: ChatRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val chatDecisionRepository: ChatDecisionRepository,
    private val matchService: MatchService,
    private val visualReviewService: VisualReviewService,
    private val penaltyService: PenaltyService,
    private val connectionService: ConnectionService,

    @Value("\${chat.first-chat.duration-minutes:1440}")
    private val firstChatDurationMinutes: Long,

    @Value("\${chat.second-chat.duration-minutes:2880}")
    private val secondChatDurationMinutes: Long,

    @Value("\${chat.first-chat.min-messages-per-user:0}")
    private val minMessagesPerUser: Int

) {

    fun findByIdOrThrow(chatId: UUID): Chat {
        return chatRepository.findById(chatId)
            .orElseThrow {
                NoSuchElementException("Chat not found: $chatId")
            }
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
        connectionId: UUID
    ): Chat {
        chatRepository
            .findByConnectionIdAndChatType(connectionId, ChatType.SECOND_CHAT)
            ?.let { return it }

        val now = OffsetDateTime.now()

        return chatRepository.save(
            Chat(
                matchId = matchId,
                connectionId = connectionId,
                chatType = ChatType.SECOND_CHAT,
                startedAt = now,
                timeoutAt = now.plusMinutes(secondChatDurationMinutes)
            )
        )
    }

    /**
     * Stores a message and updates lastMessageAt on the chat.
     *
     * Validates that the chat is still ACTIVE, has not timed out,
     * and that the sender belongs to the chat's match.
     */
    fun sendMessage(
        chatId: UUID,
        senderId: UUID,
        content: String
    ): ChatMessage {
        val chat = findByIdOrThrow(chatId)

        check(chat.status == ChatStatus.ACTIVE) {
            "Chat $chatId is not active (status: ${chat.status})"
        }

        check(OffsetDateTime.now().isBefore(chat.timeoutAt)) {
            "Chat $chatId has timed out"
        }

        val match = matchService.findByIdOrThrow(chat.matchId)

        check(senderId == match.userAId || senderId == match.userBId) {
            "User $senderId does not belong to match ${chat.matchId}"
        }

        val message = chatMessageRepository.save(
            ChatMessage(
                chatSessionId = chat.id,
                senderId = senderId,
                content = content
            )
        )

        chat.lastMessageAt = message.sentAt
        chatRepository.save(chat)

        return message
    }

    /**
     * Records an individual chat continuation decision for a user.
     * Resolves whether the user is userA or userB by comparing against the Match.
     *
     * When both decisions are registered:
     * - BOTH APPROVED -> Chat FINISHED, Match -> VISUAL_PHASE, VisualReview created
     * - ANY REJECTED -> Chat FINISHED, Match -> CHAT_REJECTED, locks released
     */
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

        val chatDecision =
            chatDecisionRepository.findByChatId(chat.id)
                ?: chatDecisionRepository.save(
                    ChatDecision(
                        chatId = chat.id,
                        matchId = match.id
                    )
                )

        if (decision == ChatContinueDecision.APPROVED && minMessagesPerUser > 0) {
            val sent = chatMessageRepository.countByChatSessionIdAndSenderId(
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

            else -> error("User $userId does not belong to match $matchId")
        }

        chatDecision.updatedAt = OffsetDateTime.now()
        chatDecisionRepository.save(chatDecision)

        val aDecision = chatDecision.userADecision
        val bDecision = chatDecision.userBDecision

        if (aDecision != null && bDecision != null) {
            chat.status = ChatStatus.FINISHED
            chat.endedAt = OffsetDateTime.now()
            chatRepository.save(chat)

            if (
                aDecision == ChatContinueDecision.APPROVED &&
                bDecision == ChatContinueDecision.APPROVED
            ) {
                matchService.transitionToVisualPhase(matchId)
                visualReviewService.initializeForMatch(matchId)
            } else {
                matchService.rejectChatPhase(matchId)
            }
        }
    }

    fun findChatDecisionOrNull(matchId: UUID): ChatDecision? =
        chatDecisionRepository.findByMatchId(matchId)

    /**
     * Ends a chat with the given terminal status: EXPIRED or ABANDONED.
     * Called by ChatTimeoutJob and InactivityCheckJob.
     *
     * FIRST_CHAT:
     * - EXPIRED / ABANDONED -> Match expired, locks released. No penalty.
     *
     * SECOND_CHAT:
     * - EXPIRED -> Connection closed, locks released. No penalty.
     * - ABANDONED -> Penalty applied to abandonedUserIds, then Connection closed.
     */
    fun endChat(
        chatId: UUID,
        finalStatus: ChatStatus,
        abandonedUserIds: List<UUID> = emptyList()
    ) {
        require(finalStatus == ChatStatus.EXPIRED || finalStatus == ChatStatus.ABANDONED) {
            "endChat only accepts EXPIRED or ABANDONED, got $finalStatus"
        }

        val chat = findByIdOrThrow(chatId)

        if (chat.status != ChatStatus.ACTIVE) return

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
    }

    /**
     * Closes the second chat as an explicit discard by [userId].
     *
     * Unilateral — any participant can close it. No penalty. (TODO: evaluate via item 10)
     * Closes the Connection and releases engagement locks.
     */
    fun closeSecondChat(
        chatId: UUID,
        userId: UUID
    ) {
        val chat = findByIdOrThrow(chatId)

        check(chat.chatType == ChatType.SECOND_CHAT) {
            "closeSecondChat is only valid for SECOND_CHAT"
        }

        check(chat.status == ChatStatus.ACTIVE) {
            "Chat $chatId is not active"
        }

        val connectionId = checkNotNull(chat.connectionId) {
            "SECOND_CHAT has no connectionId"
        }

        val connection = connectionService.findByIdOrThrow(connectionId)

        check(userId == connection.userAId || userId == connection.userBId) {
            "User $userId does not belong to connection $connectionId"
        }

        chat.status = ChatStatus.FINISHED
        chat.endedAt = OffsetDateTime.now()
        chatRepository.save(chat)

        connectionService.closeConnection(connectionId)
    }

    fun getMessages(chatId: UUID): List<ChatMessage> {
        return chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(chatId)
    }

    /**
     * Returns active chats with no messages beyond [inactivityMinutes].
     * Used by InactivityCheckJob.
     */
    fun findInactiveChats(inactivityMinutes: Long): List<Chat> {
        val threshold = OffsetDateTime.now().minusMinutes(inactivityMinutes)
        return chatRepository.findInactiveActiveChats(threshold)
    }

    /**
     * Returns active chats whose timeoutAt has passed.
     * Used by ChatTimeoutJob.
     */
    fun findTimedOutChats(): List<Chat> {
        return chatRepository.findExpiredActiveChats(
            now = OffsetDateTime.now()
        )
    }

    /**
     * Finds the active FIRST_CHAT for a given match.
     * Used by MatchController to approve the chat phase using only matchId.
     */
    fun findActiveFirstChatOrThrow(matchId: UUID): Chat {
        val chat =
            chatRepository.findByMatchIdAndChatType(matchId, ChatType.FIRST_CHAT)
                ?: throw NoSuchElementException("No FIRST_CHAT found for match: $matchId")

        check(chat.status == ChatStatus.ACTIVE) {
            "Chat for match $matchId is not active (status: ${chat.status})"
        }

        return chat
    }

    /**
     * Finds the active SECOND_CHAT for a given connection.
     * Used to obtain chatId after scheduling negotiation is confirmed.
     */
    fun findActiveSecondChatOrThrow(connectionId: UUID): Chat {
        val chat =
            chatRepository.findByConnectionIdAndChatType(
                connectionId,
                ChatType.SECOND_CHAT
            )
                ?: throw NoSuchElementException("No SECOND_CHAT found for connection: $connectionId")

        check(chat.status == ChatStatus.ACTIVE) {
            "Second chat for connection $connectionId is not active (status: ${chat.status})"
        }

        return chat
    }
}