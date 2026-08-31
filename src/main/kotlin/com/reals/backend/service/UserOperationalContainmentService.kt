package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class UserOperationalContainmentService(
    private val matchRepository: MatchRepository,
    private val connectionRepository: ConnectionRepository,
    private val chatRepository: ChatRepository,
    private val scheduleNegotiationRepository: ScheduleNegotiationRepository,
    private val activeEngagementLockRepository: ActiveEngagementLockRepository,
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService,
    private val eventPublisher: ApplicationEventPublisher
) {

    fun containUser(
        userId: UUID,
        reason: UserOperationalContainmentReason,
        now: OffsetDateTime = OffsetDateTime.now(),
        actorUserId: UUID? = null
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
            matchIds = matchIds,
            connectionIds = connectionIds,
            reason = reason,
            now = now,
            actorUserId = actorUserId
        )

        val transitionedMatches = matches.mapNotNull { closeMatchForContainedParticipant(it, now) }
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

        homeStateInvalidationService.bumpUsers(
            userIds = (
                listOf(userId) +
                    matches.flatMap { listOf(it.userAId, it.userBId) } +
                    connections.flatMap { listOf(it.userAId, it.userBId) }
                ).distinct(),
            reason = reason.homeInvalidationReason
        )
    }

    private fun closeVisibleChats(
        matchIds: List<UUID>,
        connectionIds: List<UUID>,
        reason: UserOperationalContainmentReason,
        now: OffsetDateTime,
        actorUserId: UUID?
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
            it.endedReason = reason.chatEndReason
        }
        chatRepository.saveAll(chats)
        chats.forEach { chat ->
            auditEventService.record(
                eventType = AuditEventType.CHAT_ENDED,
                aggregateType = AuditAggregateType.CHAT,
                aggregateId = chat.id,
                actorUserId = actorUserId,
                metadata = mapOf(
                    "chatType" to chat.chatType.name,
                    "status" to chat.status.name,
                    "endedReason" to reason.chatEndReason.name,
                    "matchId" to chat.matchId,
                    "connectionId" to chat.connectionId
                )
            )
            if (chat.chatType == ChatType.FIRST_CHAT) {
                eventPublisher.publishEvent(
                    FirstChatTerminatedEvent(
                        matchId = chat.matchId,
                        chatId = chat.id,
                        finalStatus = chat.status,
                        endedReason = reason.chatEndReason
                    )
                )
            }
        }
    }

    private fun closeMatchForContainedParticipant(
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

enum class UserOperationalContainmentReason(
    val chatEndReason: ChatEndReason,
    val homeInvalidationReason: String
) {
    ACCOUNT_DELETION(
        chatEndReason = ChatEndReason.USER_DELETED,
        homeInvalidationReason = "account_deletion_closed_engagements"
    ),
    ACCOUNT_BAN(
        chatEndReason = ChatEndReason.USER_BANNED,
        homeInvalidationReason = "account_ban_closed_engagements"
    )
}
