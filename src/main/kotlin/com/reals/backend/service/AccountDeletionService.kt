package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class AccountDeletionService(
    private val matchRepository: MatchRepository,
    private val connectionRepository: ConnectionRepository,
    private val chatRepository: ChatRepository,
    private val scheduleNegotiationRepository: ScheduleNegotiationRepository,
    private val activeEngagementLockRepository: ActiveEngagementLockRepository,
    private val auditEventService: AuditEventService
) {

    fun closeActiveEngagementsForDeletedUser(
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ) {
        val matches = matchRepository.findByParticipantIdAndStateIn(
            userId = userId,
            states = listOf(
                MatchState.CHAT_ACTIVE,
                MatchState.VISUAL_PHASE,
                MatchState.VISUAL_APPROVED
            )
        )
        val connections = connectionRepository.findByParticipantIdAndStateIn(
            userId = userId,
            states = listOf(
                ConnectionState.SCHEDULING_PENDING,
                ConnectionState.SCHEDULING_PHASE,
                ConnectionState.SECOND_CHAT_SCHEDULED,
                ConnectionState.SECOND_CHAT_AVAILABLE,
                ConnectionState.SECOND_CHAT
            )
        )

        val matchIds = matches.map { it.id }
        val connectionIds = connections.map { it.id }

        closeVisibleChats(
            userId = userId,
            matchIds = matchIds,
            connectionIds = connectionIds,
            now = now
        )

        val transitionedMatches = matches.mapNotNull { closeMatchForDeletedParticipant(it, now) }
        if (transitionedMatches.isNotEmpty()) {
            matchRepository.saveAll(transitionedMatches)
            transitionedMatches.forEach {
                activeEngagementLockRepository.deleteByEngagementId(it.id)
            }
        }

        if (connectionIds.isNotEmpty()) {
            scheduleNegotiationRepository.failPendingByConnectionIds(
                connectionIds = connectionIds,
                updatedAt = now
            )
        }

        if (connections.isNotEmpty()) {
            connections.forEach {
                it.state = ConnectionState.CLOSED
                it.updatedAt = now
            }
            connectionRepository.saveAll(connections)
            connections.forEach {
                activeEngagementLockRepository.deleteByEngagementId(it.id)
            }
        }
    }

    private fun closeVisibleChats(
        userId: UUID,
        matchIds: List<UUID>,
        connectionIds: List<UUID>,
        now: OffsetDateTime
    ) {
        val visibleStatuses = listOf(ChatStatus.AVAILABLE, ChatStatus.ACTIVE)
        val byMatch =
            if (matchIds.isEmpty()) {
                emptyList()
            } else {
                chatRepository.findByMatchIdInAndStatusIn(
                    matchIds = matchIds,
                    statuses = visibleStatuses
                )
            }
        val byConnection =
            if (connectionIds.isEmpty()) {
                emptyList()
            } else {
                chatRepository.findByConnectionIdInAndStatusIn(
                    connectionIds = connectionIds,
                    statuses = visibleStatuses
                )
            }

        val chats = (byMatch + byConnection).distinctBy { it.id }
        if (chats.isEmpty()) {
            return
        }

        chats.forEach {
            it.status = ChatStatus.CANCELLED
            it.endedAt = now
            it.endedReason = ChatEndReason.USER_DELETED
        }
        chatRepository.saveAll(chats)
        chats.forEach { chat ->
            auditEventService.record(
                eventType = AuditEventType.CHAT_ENDED,
                aggregateType = AuditAggregateType.CHAT,
                aggregateId = chat.id,
                actorUserId = userId,
                metadata = mapOf(
                    "chatType" to chat.chatType.name,
                    "status" to chat.status.name,
                    "endedReason" to ChatEndReason.USER_DELETED.name,
                    "matchId" to chat.matchId,
                    "connectionId" to chat.connectionId
                )
            )
        }
    }

    private fun closeMatchForDeletedParticipant(
        match: Match,
        now: OffsetDateTime
    ): Match? {
        val targetState =
            when (match.state) {
                MatchState.CHAT_ACTIVE -> MatchState.CHAT_REJECTED
                MatchState.VISUAL_PHASE -> MatchState.VISUAL_REJECTED
                MatchState.VISUAL_APPROVED -> return null
                MatchState.CHAT_REJECTED,
                MatchState.VISUAL_REJECTED,
                MatchState.EXPIRED -> return null
            }

        match.state = targetState
        match.updatedAt = now
        return match
    }
}
