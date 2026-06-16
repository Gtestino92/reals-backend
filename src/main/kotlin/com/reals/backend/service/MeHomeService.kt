package com.reals.backend.service

import com.reals.backend.controller.dto.HomeConnectionResponse
import com.reals.backend.controller.dto.HomeEngagementSummaryResponse
import com.reals.backend.controller.dto.HomeChatResponse
import com.reals.backend.controller.dto.HomeMatchmakingBlockedReasonResponse
import com.reals.backend.controller.dto.HomeMatchmakingResponse
import com.reals.backend.controller.dto.HomeMatchResponse
import com.reals.backend.controller.dto.HomeNextStepResponse
import com.reals.backend.controller.dto.HomeNextStepType
import com.reals.backend.controller.dto.HomePassiveNoticeResponse
import com.reals.backend.controller.dto.HomePassiveNoticeType
import com.reals.backend.controller.dto.HomePendingActionResponse
import com.reals.backend.controller.dto.HomePendingActionType
import com.reals.backend.controller.dto.HomeQueueResponse
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
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.repository.VisualReviewRepository
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
    private val visualReviewRepository: VisualReviewRepository,
    private val chatDecisionRepository: ChatDecisionRepository,
    private val matchmakingAvailabilityService: MatchmakingAvailabilityService
) {

    @Transactional(readOnly = true)
    fun getHome(userId: UUID): HomeResponse {
        val profileStatus = profileRepository.findByUserId(userId)?.status
        val inQueue = queueRepository.existsByUserId(userId)

        val now = OffsetDateTime.now()

        val candidateMatches = matchRepository
            .findByParticipantIdAndStateIn(
                userId = userId,
                states = listOf(
                    MatchState.CHAT_ACTIVE,
                    MatchState.VISUAL_PHASE,
                ),
            )

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
            )
            .sortedByDescending { it.updatedAt }

        val activeConnectionsForSummary = connectionRepository
            .findByParticipantIdAndStateIn(
                userId = userId,
                states = listOf(
                    ConnectionState.SCHEDULING_PENDING,
                    ConnectionState.SCHEDULING_PHASE,
                    ConnectionState.SECOND_CHAT_SCHEDULED,
                    ConnectionState.SECOND_CHAT_AVAILABLE,
                    ConnectionState.SECOND_CHAT
                )
            )

        val pendingSchedulingConnectionCount =
            activeConnectionsForSummary.count {
                it.state == ConnectionState.SCHEDULING_PENDING
            }

        val secondChatsByConnectionId = if (activeConnections.isEmpty()) {
            emptyMap()
        } else {
            chatRepository
                .findByConnectionIdInAndChatType(
                    connectionIds = activeConnections.map { it.id },
                    chatType = ChatType.SECOND_CHAT
                )
                .mapNotNull { chat ->
                    chat.connectionId?.let { connectionId -> connectionId to chat }
                }
                .toMap()
        }

        val partnerUserIds =
            activeMatches.map { partnerUserId(it.userAId, it.userBId, userId) } +
                activeConnections.map { partnerUserId(it.userAId, it.userBId, userId) }

        val partnerProfilesByUserId =
            if (partnerUserIds.isEmpty()) {
                emptyMap()
            } else {
                profileRepository
                    .findByUserIdIn(partnerUserIds)
                    .associateBy { it.userId }
            }

        val engagementSummary = HomeEngagementSummaryResponse(
            activeMatchCount = activeMatches.size,
            activeConnectionCount = activeConnectionsForSummary.size,
            pendingSchedulingConnectionCount = pendingSchedulingConnectionCount,
            actionableConnectionCount = activeConnections.size
        )

        val matchmakingAvailability = matchmakingAvailabilityService.availabilityFor(
            userId = userId,
            inQueue = inQueue
        )

        val homeMatches = activeMatches.map { match ->
            val firstChat = firstChatForCurrentUserIfActionable(
                match = match,
                currentUserId = userId,
                firstChat = firstChatsByMatchId[match.id],
                decision = chatDecisionsByMatchId[match.id],
                now = now
            )

            HomeMatchResponse.from(
                match = match,
                firstChat = firstChat,
                partner = partnerProfilesByUserId[
                    partnerUserId(match.userAId, match.userBId, userId)
                ]
            )
        }

        val homeConnections = activeConnections.map { connection ->
            HomeConnectionResponse.from(
                connection = connection,
                secondChat = secondChatsByConnectionId[connection.id],
                partner = partnerProfilesByUserId[
                    partnerUserId(connection.userAId, connection.userBId, userId)
                ]
            )
        }

        return HomeResponse(
            profileStatus = profileStatus,
            engagementSummary = engagementSummary,
            queue = HomeQueueResponse(
                inQueue = inQueue
            ),
            activeMatches = homeMatches,
            activeConnections = homeConnections,
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
            pendingActions = activeMatches.mapNotNull { match ->
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
            },
            nextSteps = activeConnections.mapNotNull { connection ->
                toNextStep(
                    connection = connection,
                    secondChat = secondChatsByConnectionId[connection.id],
                    partner = partnerProfilesByUserId[
                        partnerUserId(connection.userAId, connection.userBId, userId)
                    ]
                )
            },
            passiveNotices = passiveNoticesFor(engagementSummary)
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

    private fun toNextStep(
        connection: Connection,
        secondChat: Chat?,
        partner: Profile?
    ): HomeNextStepResponse? {
        val type = when (connection.state) {
            ConnectionState.SCHEDULING_PHASE -> HomeNextStepType.SCHEDULING
            ConnectionState.SECOND_CHAT_SCHEDULED -> HomeNextStepType.SECOND_CHAT_SCHEDULED
            ConnectionState.SECOND_CHAT_AVAILABLE,
            ConnectionState.SECOND_CHAT -> HomeNextStepType.SECOND_CHAT_AVAILABLE
            ConnectionState.SCHEDULING_PENDING,
            ConnectionState.CLOSED -> return null
        }

        return HomeNextStepResponse(
            type = type,
            connectionId = connection.id,
            matchId = connection.matchId,
            partner = partner?.let { PartnerSummaryResponse.from(it) },
            secondChat = secondChat?.let {
                HomeChatResponse.from(
                    chat = it,
                    partner = partner
                )
            }
        )
    }

    private fun passiveNoticesFor(
        engagementSummary: HomeEngagementSummaryResponse
    ): List<HomePassiveNoticeResponse> =
        if (engagementSummary.pendingSchedulingConnectionCount > 0) {
            listOf(
                HomePassiveNoticeResponse(
                    type = HomePassiveNoticeType.SCHEDULING_PREPARING,
                    count = engagementSummary.pendingSchedulingConnectionCount
                )
            )
        } else {
            emptyList()
        }
}

