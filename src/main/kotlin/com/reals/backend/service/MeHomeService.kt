package com.reals.backend.service

import com.reals.backend.controller.dto.HomeActiveInteractionsSummaryResponse
import com.reals.backend.controller.dto.HomeChatResponse
import com.reals.backend.controller.dto.HomeMatchmakingBlockedReasonResponse
import com.reals.backend.controller.dto.HomeMatchmakingResponse
import com.reals.backend.controller.dto.HomeNextStepResponse
import com.reals.backend.controller.dto.HomeNextStepType
import com.reals.backend.controller.dto.HomeNextStepLiteResponse
import com.reals.backend.controller.dto.HomePassiveNoticeResponse
import com.reals.backend.controller.dto.HomePassiveNoticeType
import com.reals.backend.controller.dto.HomePendingActionResponse
import com.reals.backend.controller.dto.HomePendingActionLiteResponse
import com.reals.backend.controller.dto.HomePendingActionType
import com.reals.backend.controller.dto.HomePendingSecondChatLiteResponse
import com.reals.backend.controller.dto.HomePendingStateResponse
import com.reals.backend.controller.dto.HomeResponse
import com.reals.backend.controller.dto.PartnerSummaryResponse
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatDecision
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.Profile
import com.reals.backend.domain.VisualReview
import com.reals.backend.repository.ChatDecisionRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionHomeDismissalRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.matching.MatchmakingAvailabilityService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class MeHomeService(
    private val profileRepository: ProfileRepository,
    private val queueRepository: MatchmakingQueueRepository,
    private val matchRepository: MatchRepository,
    private val chatRepository: ChatRepository,
    private val connectionRepository: ConnectionRepository,
    private val dismissalRepository: ConnectionHomeDismissalRepository,
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val visualReviewRepository: VisualReviewRepository,
    private val chatDecisionRepository: ChatDecisionRepository,
    private val matchmakingAvailabilityService: MatchmakingAvailabilityService,
    private val homeStatusService: HomeStatusService,
    private val userBlockService: UserBlockService,

    @param:Value("\${chat.second-chat.duration-minutes:120}")
    private val secondChatDurationMinutes: Long
) {

    @Transactional(readOnly = true)
    fun getHome(userId: UUID): HomeResponse {
        val profileStatus = profileRepository.findByUserId(userId)?.status
        val inQueue = queueRepository.existsByUserId(userId)

        val now = OffsetDateTime.now()
        val blockedCounterpartIds = userBlockService.findBlockedCounterpartUserIds(userId)

        val candidateMatches = matchRepository
            .findByParticipantIdAndStateIn(
                userId = userId,
                states = listOf(
                    MatchState.CHAT_ACTIVE,
                    MatchState.VISUAL_PHASE,
                ),
            ).filterNot { it.counterpartIdFor(userId) in blockedCounterpartIds }

        val visualReviewByMatchId = candidateMatches
            .filter { it.state == MatchState.VISUAL_PHASE }
            .takeIf { it.isNotEmpty() }
            ?.let { visualMatches ->
                visualReviewRepository
                    .findByMatchIdIn(visualMatches.map { it.id })
                    .associateBy { it.matchId }
            }
            ?: emptyMap()

        val activeMatches = candidateMatches
            .filter { match ->
                if (match.state != MatchState.VISUAL_PHASE) {
                    true
                } else {
                    val review = visualReviewByMatchId[match.id]
                    review?.expiresAt?.isAfter(now) == true &&
                        review.hasPendingDecisionFor(
                            userId = userId,
                            userAId = match.userAId,
                            userBId = match.userBId
                        )
                }
            }
            .sortedByDescending { it.updatedAt }

        val firstChatsByMatchId = if (activeMatches.isEmpty()) {
            emptyMap()
        } else {
            chatRepository
                .findByMatchIdInAndChatType(
                    matchIds = activeMatches.map { it.id },
                    chatType = ChatType.FIRST_CHAT
                )
                .associateBy { it.matchId }
        }

        val chatDecisionsByMatchId: Map<UUID, ChatDecision?> =
            activeMatches
                .filter { it.state == MatchState.CHAT_ACTIVE }
                .associate { match ->
                    match.id to chatDecisionRepository.findByMatchId(match.id)
                }

        val activeConnections = connectionRepository
            .findByParticipantIdAndStateIn(
                userId = userId,
                states = listOf(
                    ConnectionState.SCHEDULING_PHASE,
                    ConnectionState.SECOND_CHAT_SCHEDULED,
                    ConnectionState.SECOND_CHAT_AVAILABLE,
                    ConnectionState.SECOND_CHAT
                )
            ).filterNot { it.counterpartIdFor(userId) in blockedCounterpartIds }
            .sortedByDescending { it.updatedAt }

        val dismissedConnectionIds =
            if (activeConnections.isEmpty()) {
                emptySet()
            } else {
                dismissalRepository
                    .findDismissedConnectionIds(
                        userId = userId,
                        connectionIds = activeConnections.map { it.id }
                    )
                    .toSet()
            }

        val visibleConnections =
            activeConnections.filter { it.id !in dismissedConnectionIds }

        val pendingSchedulingConnections = pendingSchedulingConnections(
            userId = userId,
            blockedCounterpartIds = blockedCounterpartIds
        )

        val secondChatsByConnectionId = if (visibleConnections.isEmpty()) {
            emptyMap()
        } else {
            chatRepository
                .findByConnectionIdInAndChatType(
                    connectionIds = visibleConnections.map { it.id },
                    chatType = ChatType.SECOND_CHAT
                )
                .mapNotNull { chat ->
                    chat.connectionId?.let { connectionId -> connectionId to chat }
                }
                .toMap()
        }

        val confirmedNegotiationsByConnectionId = if (visibleConnections.isEmpty()) {
            emptyMap()
        } else {
            negotiationRepository
                .findByConnectionIdIn(visibleConnections.map { it.id })
                .filter { it.confirmedDateTime != null }
                .associateBy { it.connectionId }
        }

        val partnerUserIds =
            activeMatches.map { partnerUserId(it.userAId, it.userBId, userId) } +
                visibleConnections.map { partnerUserId(it.userAId, it.userBId, userId) }

        val partnerProfilesByUserId =
            if (partnerUserIds.isEmpty()) {
                emptyMap()
            } else {
                profileRepository
                    .findByUserIdIn(partnerUserIds)
                    .associateBy { it.userId }
            }

        val pendingActions = activeMatches.mapNotNull { match ->
            toPendingAction(
                match = match,
                currentUserId = userId,
                firstChat = firstChatsByMatchId[match.id],
                decision = chatDecisionsByMatchId[match.id],
                visualReview = visualReviewByMatchId[match.id],
                partner = partnerProfilesByUserId[
                    partnerUserId(match.userAId, match.userBId, userId)
                ],
                now = now
            )
        }

        val nextSteps = visibleConnections.mapNotNull { connection ->
            toNextStep(
                connection = connection,
                secondChat = secondChatsByConnectionId[connection.id],
                secondChatAvailableAt = confirmedNegotiationsByConnectionId[
                    connection.id
                ]?.confirmedDateTime,
                partner = partnerProfilesByUserId[
                    partnerUserId(connection.userAId, connection.userBId, userId)
                ],
                now = now
            )
        }

        val hasPendingSchedulingConnection = pendingSchedulingConnections.isNotEmpty()

        val activeInteractionsSummary = HomeActiveInteractionsSummaryResponse(
            activeInitialCount = pendingActions.size,
            activeConnectionCount = nextSteps.size,
            hasPendingSchedulingConnection = hasPendingSchedulingConnection,
            actionableConnectionCount = nextSteps.size
        )

        val matchmakingAvailability = matchmakingAvailabilityService.availabilityFor(
            userId = userId,
            inQueue = inQueue
        )

        return HomeResponse(
            profileStatus = profileStatus,
            matchmaking = HomeMatchmakingResponse(
                inQueue = inQueue,
                canSearch = matchmakingAvailability.canSearch,
                blockedReason = matchmakingAvailability.blockedReason?.let {
                    HomeMatchmakingBlockedReasonResponse(
                        code = it.code,
                        message = it.message
                    )
                }
            ),
            activeInteractionsSummary = activeInteractionsSummary,
            pendingActions = pendingActions,
            nextSteps = nextSteps,
            passiveNotices = passiveNoticesForPendingScheduling(
                hasPendingSchedulingConnection
            )
        )
    }

    @Transactional
    fun getPendingHomeState(userId: UUID): HomePendingStateResponse {
        val now = OffsetDateTime.now()
        val status = homeStatusService.getOrCreateStatus(userId = userId)
        val blockedCounterpartIds = userBlockService.findBlockedCounterpartUserIds(userId)

        val candidateMatches = matchRepository
            .findByParticipantIdAndStateIn(
                userId = userId,
                states = listOf(
                    MatchState.CHAT_ACTIVE,
                    MatchState.VISUAL_PHASE,
                ),
            ).filterNot { it.counterpartIdFor(userId) in blockedCounterpartIds }

        val visualReviewByMatchId = candidateMatches
            .filter { it.state == MatchState.VISUAL_PHASE }
            .takeIf { it.isNotEmpty() }
            ?.let { visualMatches ->
                visualReviewRepository
                    .findByMatchIdIn(visualMatches.map { it.id })
                    .associateBy { it.matchId }
            }
            ?: emptyMap()

        val activeMatches = candidateMatches
            .filter { match ->
                if (match.state != MatchState.VISUAL_PHASE) {
                    true
                } else {
                    val review = visualReviewByMatchId[match.id]
                    review?.expiresAt?.isAfter(now) == true &&
                        review.hasPendingDecisionFor(
                            userId = userId,
                            userAId = match.userAId,
                            userBId = match.userBId
                        )
                }
            }
            .sortedByDescending { it.updatedAt }

        val firstChatsByMatchId = if (activeMatches.isEmpty()) {
            emptyMap()
        } else {
            chatRepository
                .findByMatchIdInAndChatType(
                    matchIds = activeMatches.map { it.id },
                    chatType = ChatType.FIRST_CHAT
                )
                .associateBy { it.matchId }
        }

        val chatDecisionsByMatchId: Map<UUID, ChatDecision?> =
            activeMatches
                .filter { it.state == MatchState.CHAT_ACTIVE }
                .associate { match ->
                    match.id to chatDecisionRepository.findByMatchId(match.id)
                }

        val visibleConnections = visibleConnectionsForPending(
            userId = userId,
            blockedCounterpartIds = blockedCounterpartIds
        )

        val secondChatsByConnectionId = if (visibleConnections.isEmpty()) {
            emptyMap()
        } else {
            chatRepository
                .findByConnectionIdInAndChatType(
                    connectionIds = visibleConnections.map { it.id },
                    chatType = ChatType.SECOND_CHAT
                )
                .mapNotNull { chat ->
                    chat.connectionId?.let { connectionId -> connectionId to chat }
                }
                .toMap()
        }

        val confirmedNegotiationsByConnectionId = if (visibleConnections.isEmpty()) {
            emptyMap()
        } else {
            negotiationRepository
                .findByConnectionIdIn(visibleConnections.map { it.id })
                .filter { it.confirmedDateTime != null }
                .associateBy { it.connectionId }
        }

        val hasPendingSchedulingConnection = pendingSchedulingConnections(
            userId = userId,
            blockedCounterpartIds = blockedCounterpartIds
        ).isNotEmpty()

        return HomePendingStateResponse(
            version = status.version,
            pendingActions = activeMatches.mapNotNull { match ->
                toPendingActionLite(
                    match = match,
                    currentUserId = userId,
                    firstChat = firstChatsByMatchId[match.id],
                    decision = chatDecisionsByMatchId[match.id],
                    visualReview = visualReviewByMatchId[match.id],
                    now = now
                )
            },
            nextSteps = visibleConnections.mapNotNull { connection ->
                toNextStepLite(
                    connection = connection,
                    secondChat = secondChatsByConnectionId[connection.id],
                    secondChatAvailableAt = confirmedNegotiationsByConnectionId[
                        connection.id
                    ]?.confirmedDateTime,
                    now = now
                )
            },
            passiveNotices = passiveNoticesForPendingScheduling(
                hasPendingSchedulingConnection
            ),
            serverTime = OffsetDateTime.now()
        )
    }

    private fun partnerUserId(
        userAId: UUID,
        userBId: UUID,
        currentUserId: UUID
    ): UUID =
        when (currentUserId) {
            userAId -> userBId
            userBId -> userAId
            else -> error("Current user is not a participant")
        }

    private fun hasPendingFirstChatDecisionForCurrentUser(
        match: Match,
        currentUserId: UUID,
        decision: ChatDecision?,
    ): Boolean {
        val myDecision = when (currentUserId) {
            match.userAId -> decision?.userADecision
            match.userBId -> decision?.userBDecision
            else -> error("Current user is not a participant")
        }

        return myDecision == null
    }

    private fun firstChatForCurrentUserIfActionable(
        match: Match,
        currentUserId: UUID,
        firstChat: Chat?,
        decision: ChatDecision?,
        now: OffsetDateTime
    ): Chat? =
        firstChat?.takeIf {
            match.state == MatchState.CHAT_ACTIVE &&
                it.status == ChatStatus.ACTIVE &&
                it.timeoutAt.isAfter(now) &&
                hasPendingFirstChatDecisionForCurrentUser(
                    match = match,
                    currentUserId = currentUserId,
                    decision = decision,
                )
        }

    private fun isVisualReviewActionableForCurrentUser(
        match: Match,
        currentUserId: UUID,
        visualReview: VisualReview?,
        now: OffsetDateTime
    ): Boolean =
        match.state == MatchState.VISUAL_PHASE &&
            visualReview?.expiresAt?.isAfter(now) == true &&
            visualReview.hasPendingDecisionFor(
                userId = currentUserId,
                userAId = match.userAId,
                userBId = match.userBId
            )

    private fun toPendingAction(
        match: Match,
        currentUserId: UUID,
        firstChat: Chat?,
        decision: ChatDecision?,
        visualReview: VisualReview?,
        partner: Profile?,
        now: OffsetDateTime
    ): HomePendingActionResponse? {
        val partnerSummary = partner?.let { PartnerSummaryResponse.from(it) }

        firstChatForCurrentUserIfActionable(
            match = match,
            currentUserId = currentUserId,
            firstChat = firstChat,
            decision = decision,
            now = now
        )?.let { actionableFirstChat ->
            return HomePendingActionResponse(
                type = HomePendingActionType.FIRST_CHAT,
                matchId = match.id,
                chatId = actionableFirstChat.id,
                partner = partnerSummary
            )
        }

        if (
            isVisualReviewActionableForCurrentUser(
                match = match,
                currentUserId = currentUserId,
                visualReview = visualReview,
                now = now
            )
        ) {
            return HomePendingActionResponse(
                type = HomePendingActionType.VISUAL_REVIEW,
                matchId = match.id,
                chatId = null,
                partner = partnerSummary
            )
        }

        return null
    }

    private fun toPendingActionLite(
        match: Match,
        currentUserId: UUID,
        firstChat: Chat?,
        decision: ChatDecision?,
        visualReview: VisualReview?,
        now: OffsetDateTime
    ): HomePendingActionLiteResponse? {
        firstChatForCurrentUserIfActionable(
            match = match,
            currentUserId = currentUserId,
            firstChat = firstChat,
            decision = decision,
            now = now
        )?.let { actionableFirstChat ->
            return HomePendingActionLiteResponse(
                type = HomePendingActionType.FIRST_CHAT,
                matchId = match.id,
                chatId = actionableFirstChat.id
            )
        }

        if (
            isVisualReviewActionableForCurrentUser(
                match = match,
                currentUserId = currentUserId,
                visualReview = visualReview,
                now = now
            )
        ) {
            return HomePendingActionLiteResponse(
                type = HomePendingActionType.VISUAL_REVIEW,
                matchId = match.id,
                chatId = null
            )
        }

        return null
    }

    private fun toNextStep(
        connection: Connection,
        secondChat: Chat?,
        secondChatAvailableAt: OffsetDateTime?,
        partner: Profile?,
        now: OffsetDateTime
    ): HomeNextStepResponse? {
        val type = when (connection.state) {
            ConnectionState.SCHEDULING_PHASE -> HomeNextStepType.SCHEDULING
            ConnectionState.SECOND_CHAT_SCHEDULED -> {
                if (isScheduledSecondChatWindowExpired(secondChatAvailableAt, now)) {
                    return null
                }
                HomeNextStepType.SECOND_CHAT_SCHEDULED
            }
            ConnectionState.SECOND_CHAT_AVAILABLE -> HomeNextStepType.SECOND_CHAT_AVAILABLE
            ConnectionState.SECOND_CHAT -> secondChatNextStepType(
                secondChat = secondChat,
                now = now
            ) ?: return null
            ConnectionState.SCHEDULING_PENDING,
            ConnectionState.CLOSED -> return null
        }

        return HomeNextStepResponse(
            type = type,
            connectionId = connection.id,
            matchId = connection.matchId,
            partner = partner?.let { PartnerSummaryResponse.from(it) },
            secondChat = secondChatResponse(
                chat = secondChat,
                availableAt = secondChatAvailableAt,
                partner = partner
            )
        )
    }

    private fun toNextStepLite(
        connection: Connection,
        secondChat: Chat?,
        secondChatAvailableAt: OffsetDateTime?,
        now: OffsetDateTime
    ): HomeNextStepLiteResponse? {
        val type = when (connection.state) {
            ConnectionState.SCHEDULING_PHASE -> HomeNextStepType.SCHEDULING
            ConnectionState.SECOND_CHAT_SCHEDULED -> {
                if (isScheduledSecondChatWindowExpired(secondChatAvailableAt, now)) {
                    return null
                }
                HomeNextStepType.SECOND_CHAT_SCHEDULED
            }
            ConnectionState.SECOND_CHAT_AVAILABLE -> HomeNextStepType.SECOND_CHAT_AVAILABLE
            ConnectionState.SECOND_CHAT -> secondChatNextStepType(
                secondChat = secondChat,
                now = now
            ) ?: return null
            ConnectionState.SCHEDULING_PENDING,
            ConnectionState.CLOSED -> return null
        }

        return HomeNextStepLiteResponse(
            type = type,
            connectionId = connection.id,
            matchId = connection.matchId,
            secondChat = secondChatLiteResponse(
                chat = secondChat,
                availableAt = secondChatAvailableAt
            )
        )
    }

    private fun visibleConnectionsForPending(
        userId: UUID,
        blockedCounterpartIds: Set<UUID>
    ): List<Connection> {
        val activeConnections = connectionRepository
            .findByParticipantIdAndStateIn(
                userId = userId,
                states = listOf(
                    ConnectionState.SCHEDULING_PHASE,
                    ConnectionState.SECOND_CHAT_SCHEDULED,
                    ConnectionState.SECOND_CHAT_AVAILABLE,
                    ConnectionState.SECOND_CHAT
                )
            ).filterNot { it.counterpartIdFor(userId) in blockedCounterpartIds }
            .sortedByDescending { it.updatedAt }

        val dismissedConnectionIds =
            if (activeConnections.isEmpty()) {
                emptySet()
            } else {
                dismissalRepository
                    .findDismissedConnectionIds(
                        userId = userId,
                        connectionIds = activeConnections.map { it.id }
                    )
                    .toSet()
            }

        return activeConnections.filter { it.id !in dismissedConnectionIds }
    }

    private fun pendingSchedulingConnections(
        userId: UUID,
        blockedCounterpartIds: Set<UUID>
    ): List<Connection> {
        val pendingConnections = connectionRepository
            .findByParticipantIdAndStateIn(
                userId = userId,
                states = listOf(ConnectionState.SCHEDULING_PENDING)
            ).filterNot { it.counterpartIdFor(userId) in blockedCounterpartIds }

        val dismissedConnectionIds =
            if (pendingConnections.isEmpty()) {
                emptySet()
            } else {
                dismissalRepository
                    .findDismissedConnectionIds(
                        userId = userId,
                        connectionIds = pendingConnections.map { it.id }
                    )
                    .toSet()
            }

        return pendingConnections.filter { it.id !in dismissedConnectionIds }
    }

    private fun isScheduledSecondChatWindowExpired(
        availableAt: OffsetDateTime?,
        now: OffsetDateTime
    ): Boolean {
        val expiresAt = availableAt?.plusMinutes(secondChatDurationMinutes)
            ?: return false

        return !expiresAt.isAfter(now)
    }

    private fun secondChatResponse(
        chat: Chat?,
        availableAt: OffsetDateTime?,
        partner: Profile?
    ): HomeChatResponse? {
        val resolvedAvailableAt = availableAt ?: chat?.availableAt ?: return null
        val expiresAt = chat?.timeoutAt ?: resolvedAvailableAt.plusMinutes(secondChatDurationMinutes)

        return HomeChatResponse.from(
            chat = chat,
            availableAt = resolvedAvailableAt,
            expiresAt = expiresAt,
            readOnlyUntil = chat?.readOnlyUntil,
            durationMinutes = secondChatDurationMinutes,
            partner = partner
        )
    }

    private fun secondChatLiteResponse(
        chat: Chat?,
        availableAt: OffsetDateTime?
    ): HomePendingSecondChatLiteResponse? {
        val resolvedAvailableAt = availableAt ?: chat?.availableAt ?: return null
        val expiresAt = chat?.timeoutAt ?: resolvedAvailableAt.plusMinutes(secondChatDurationMinutes)

        return HomePendingSecondChatLiteResponse(
            chatId = chat?.id,
            availableAt = resolvedAvailableAt,
            expiresAt = expiresAt,
            readOnlyUntil = chat?.readOnlyUntil,
            durationMinutes = secondChatDurationMinutes
        )
    }

    private fun secondChatNextStepType(
        secondChat: Chat?,
        now: OffsetDateTime
    ): HomeNextStepType? {
        return when (secondChat?.status) {
            ChatStatus.EXPIRED ->
                if (secondChat.readOnlyUntil?.isAfter(now) == true) {
                    HomeNextStepType.SECOND_CHAT_READ_ONLY
                } else {
                    null
                }

            ChatStatus.CLOSED -> null
            else -> HomeNextStepType.SECOND_CHAT_AVAILABLE
        }
    }

    private fun passiveNoticesForPendingScheduling(
        hasPendingSchedulingConnection: Boolean
    ): List<HomePassiveNoticeResponse> =
        if (hasPendingSchedulingConnection) {
            listOf(
                HomePassiveNoticeResponse(
                    type = HomePassiveNoticeType.SCHEDULING_PREPARING
                )
            )
        } else {
            emptyList()
        }
}

private fun Match.counterpartIdFor(userId: UUID): UUID =
    if (userAId == userId) userBId else userAId

private fun Connection.counterpartIdFor(userId: UUID): UUID =
    if (userAId == userId) userBId else userAId

