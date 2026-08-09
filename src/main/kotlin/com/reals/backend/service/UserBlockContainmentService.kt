package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.MatchRepository
import jakarta.transaction.Transactional
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class UserBlockContainmentService(
    private val matchRepository: MatchRepository,
    private val connectionRepository: ConnectionRepository,
    private val chatRepository: ChatRepository,
    private val matchService: MatchService,
    private val connectionService: ConnectionService,
    private val auditEventService: AuditEventService,
    private val eventPublisher: ApplicationEventPublisher
) {
    fun containPair(userAId: UUID, userBId: UUID) {
        matchRepository.findBetweenUsersAndStateIn(
            userAId, userBId, listOf(MatchState.CHAT_ACTIVE, MatchState.VISUAL_PHASE)
        ).forEach { match ->
            when (match.state) {
                MatchState.CHAT_ACTIVE -> {
                    chatRepository.findByMatchIdAndChatType(match.id, ChatType.FIRST_CHAT)
                        ?.takeIf { it.status == ChatStatus.ACTIVE }
                        ?.let { cancelChat(it) }
                    matchService.rejectChatPhase(match.id)
                }
                MatchState.VISUAL_PHASE -> matchService.rejectVisualPhase(match.id)
                else -> Unit
            }
        }

        connectionRepository.findBetweenUsersAndStateIn(
            userAId, userBId, listOf(
                ConnectionState.SCHEDULING_PENDING, ConnectionState.SCHEDULING_PHASE,
                ConnectionState.SECOND_CHAT_SCHEDULED, ConnectionState.SECOND_CHAT_AVAILABLE,
                ConnectionState.SECOND_CHAT
            )
        ).forEach { connection ->
            chatRepository.findByConnectionIdAndChatType(connection.id, ChatType.SECOND_CHAT)
                ?.takeIf { it.status == ChatStatus.AVAILABLE || it.status == ChatStatus.ACTIVE }
                ?.let { cancelChat(it) }
            connectionService.closeConnection(connection.id)
        }
    }

    private fun cancelChat(chat: Chat) {
        chat.status = ChatStatus.CANCELLED
        chat.endedReason = ChatEndReason.USER_BLOCK
        chat.endedAt = OffsetDateTime.now()
        chatRepository.save(chat)
        auditEventService.record(
            eventType = AuditEventType.CHAT_ENDED,
            aggregateType = AuditAggregateType.CHAT,
            aggregateId = chat.id,
            metadata = mapOf(
                "chatType" to chat.chatType.name,
                "endedReason" to ChatEndReason.USER_BLOCK.name,
                "status" to ChatStatus.CANCELLED.name
            )
        )
        if (chat.chatType == ChatType.FIRST_CHAT) {
            eventPublisher.publishEvent(
                FirstChatTerminatedEvent(
                    matchId = chat.matchId,
                    chatId = chat.id,
                    finalStatus = chat.status,
                    endedReason = ChatEndReason.USER_BLOCK
                )
            )
        }
    }
}
