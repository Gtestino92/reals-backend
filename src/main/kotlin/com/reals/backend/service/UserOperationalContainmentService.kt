package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.VisualReviewRepository
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
    private val visualReviewRepository: VisualReviewRepository,
    private val activeEngagementLockRepository: ActiveEngagementLockRepository,
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService,
    private val eventPublisher: ApplicationEventPublisher,
    private val accountBanPolicyService: AccountBanPolicyService
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

        containSelectedEngagements(
            userId = userId,
            matches = matches,
            connections = connections,
            reason = reason,
            now = now,
            actorUserId = actorUserId,
            alwaysInvalidateContainedUser = true
        )
    }

    fun containTemporarilyBannedUser(
        userId: UUID,
        effectiveBanExpiresAt: OffsetDateTime,
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
        ).filter {
            shouldContainMatchForTemporaryBan(
                match = it,
                userId = userId,
                effectiveBanExpiresAt = effectiveBanExpiresAt
            )
        }
        val connections = connectionRepository.findByParticipantIdAndStateIn(
            userId = userId,
            states = listOf(
                ConnectionState.SCHEDULING_PENDING,
                ConnectionState.SCHEDULING_PHASE,
                ConnectionState.SECOND_CHAT_SCHEDULED,
                ConnectionState.SECOND_CHAT_AVAILABLE,
                ConnectionState.SECOND_CHAT
            )
        ).filter {
            shouldContainConnectionForTemporaryBan(
                connection = it,
                effectiveBanExpiresAt = effectiveBanExpiresAt
            )
        }

        containSelectedEngagements(
            userId = userId,
            matches = matches,
            connections = connections,
            reason = UserOperationalContainmentReason.ACCOUNT_BAN,
            now = now,
            actorUserId = actorUserId,
            alwaysInvalidateContainedUser = false
        )
    }

    private fun containSelectedEngagements(
        userId: UUID,
        matches: List<Match>,
        connections: List<Connection>,
        reason: UserOperationalContainmentReason,
        now: OffsetDateTime,
        actorUserId: UUID?,
        alwaysInvalidateContainedUser: Boolean
    ) {
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

        val affectedUserIds =
            (
                (if (alwaysInvalidateContainedUser) listOf(userId) else emptyList()) +
                    matches.flatMap { listOf(it.userAId, it.userBId) } +
                    connections.flatMap { listOf(it.userAId, it.userBId) }
                ).distinct()
        if (affectedUserIds.isNotEmpty()) {
            homeStateInvalidationService.bumpUsers(
                userIds = affectedUserIds,
                reason = reason.homeInvalidationReason
            )
        }
    }

    private fun shouldContainMatchForTemporaryBan(
        match: Match,
        userId: UUID,
        effectiveBanExpiresAt: OffsetDateTime
    ): Boolean =
        when (match.state) {
            MatchState.CHAT_ACTIVE -> true
            MatchState.VISUAL_PHASE -> shouldContainVisualPhaseForTemporaryBan(
                match = match,
                userId = userId,
                effectiveBanExpiresAt = effectiveBanExpiresAt
            )
            MatchState.VISUAL_APPROVED -> false
            MatchState.CHAT_REJECTED,
            MatchState.VISUAL_REJECTED,
            MatchState.EXPIRED -> false
        }

    private fun shouldContainVisualPhaseForTemporaryBan(
        match: Match,
        userId: UUID,
        effectiveBanExpiresAt: OffsetDateTime
    ): Boolean {
        val review = visualReviewRepository.findByMatchId(match.id) ?: return true
        if (!review.hasPendingDecisionFor(userId, match.userAId, match.userBId)) {
            return false
        }
        val expiresAt = review.expiresAt ?: return true
        return !accountBanPolicyService.isTemporaryBanDeadlineResumable(
            effectiveBanExpiresAt = effectiveBanExpiresAt,
            deadline = expiresAt
        )
    }

    private fun shouldContainConnectionForTemporaryBan(
        connection: Connection,
        effectiveBanExpiresAt: OffsetDateTime
    ): Boolean =
        when (connection.state) {
            ConnectionState.SCHEDULING_PENDING,
            ConnectionState.SCHEDULING_PHASE ->
                !accountBanPolicyService.isTemporaryBanDeadlineResumable(
                    effectiveBanExpiresAt = effectiveBanExpiresAt,
                    deadline = connection.schedulingExpiresAt
                )

            ConnectionState.SECOND_CHAT_SCHEDULED,
            ConnectionState.SECOND_CHAT_AVAILABLE ->
                shouldContainScheduledSecondChatForTemporaryBan(
                    connectionId = connection.id,
                    effectiveBanExpiresAt = effectiveBanExpiresAt
                )

            ConnectionState.SECOND_CHAT -> true
            ConnectionState.CLOSED -> false
        }

    private fun shouldContainScheduledSecondChatForTemporaryBan(
        connectionId: UUID,
        effectiveBanExpiresAt: OffsetDateTime
    ): Boolean {
        val confirmedDateTime =
            scheduleNegotiationRepository.findByConnectionId(connectionId)
                ?.takeIf { it.status == NegotiationStatus.CONFIRMED }
                ?.confirmedDateTime
                ?: return true
        return !accountBanPolicyService.isTemporaryBanDeadlineResumable(
            effectiveBanExpiresAt = effectiveBanExpiresAt,
            deadline = accountBanPolicyService.secondChatEntryClosesAt(confirmedDateTime)
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
