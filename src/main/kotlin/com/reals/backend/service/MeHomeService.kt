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
import com.reals.backend.domain.ScheduleNegotiation
import com.reals.backend.domain.SecondChatAttendanceStatus
import com.reals.backend.domain.VisualReview
import com.reals.backend.repository.ChatDecisionRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionHomeDismissalRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.SecondChatParticipationRepository
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
    private val participationRepository: SecondChatParticipationRepository,
    private val visualReviewRepository: VisualReviewRepository,
    private val chatDecisionRepository: ChatDecisionRepository,
    private val matchmakingAvailabilityService: MatchmakingAvailabilityService,
    private val homeStatusService: HomeStatusService,
    private val userBlockService: UserBlockService,
    private val readMetrics: ReadMetrics,

    @param:Value("\${chat.second-chat.duration-minutes:120}")
    private val secondChatDurationMinutes: Long,

    @param:Value("\${chat.second-chat.entry-window-minutes:20}")
    private val entryWindowMinutes: Long,

    @param:Value("\${chat.second-chat.read-only-retention-minutes:1440}")
    private val secondChatReadOnlyRetentionMinutes: Long
) {

    data class HomeProjection(
        val home: HomeResponse,
        val nextRefreshAt: OffsetDateTime?
    )

    private data class HomeOperationalSnapshot(
        val now: OffsetDateTime,
        val blockedCounterpartIds: Set<UUID>,
        val activeMatches: List<Match>,
        val visualReviewByMatchId: Map<UUID, VisualReview>,
        val firstChatsByMatchId: Map<UUID, Chat>,
        val chatDecisionsByMatchId: Map<UUID, ChatDecision?>,
        val visibleConnections: List<Connection>,
        val pendingSchedulingConnections: List<Connection>,
        val secondChatsByConnectionId: Map<UUID, Chat>,
        val confirmedNegotiationsByConnectionId: Map<UUID, com.reals.backend.domain.ScheduleNegotiation>,
        val myAttendanceStatusByConnectionId: Map<UUID, SecondChatAttendanceStatus>
    )

    @Transactional(readOnly = true)
    fun getHome(userId: UUID): HomeResponse =
        getHomeProjection(userId).home

    @Transactional(readOnly = true)
    fun getHomeProjection(userId: UUID): HomeProjection =
        readMetrics.recordHomeLoad(ReadMetrics.HOME_VARIANT_FULL) {
            getHomeProjectionMeasured(userId)
        }

    private fun getHomeProjectionMeasured(userId: UUID): HomeProjection {
        val profileStatus = profileRepository.findByUserId(userId)?.status
        val inQueue = queueRepository.existsByUserId(userId)
        val snapshot = loadHomeOperationalSnapshot(userId)

        val partnerUserIds =
            snapshot.activeMatches.map { partnerUserId(it.userAId, it.userBId, userId) } +
                snapshot.visibleConnections.map { partnerUserId(it.userAId, it.userBId, userId) }

        val partnerProfilesByUserId =
            if (partnerUserIds.isEmpty()) {
                emptyMap()
            } else {
                profileRepository
                    .findByUserIdIn(partnerUserIds)
                    .associateBy { it.userId }
            }

        val pendingActions = snapshot.activeMatches.mapNotNull { match ->
            toPendingAction(
                match = match,
                currentUserId = userId,
                firstChat = snapshot.firstChatsByMatchId[match.id],
                decision = snapshot.chatDecisionsByMatchId[match.id],
                visualReview = snapshot.visualReviewByMatchId[match.id],
                partner = partnerProfilesByUserId[
                    partnerUserId(match.userAId, match.userBId, userId)
                ],
                now = snapshot.now
            )
        }

        val nextSteps = snapshot.visibleConnections.mapNotNull { connection ->
            toNextStep(
                connection = connection,
                secondChat = snapshot.secondChatsByConnectionId[connection.id],
                secondChatAvailableAt = snapshot.confirmedNegotiationsByConnectionId[
                    connection.id
                ]?.confirmedDateTime,
                myAttendanceStatus = snapshot.myAttendanceStatusByConnectionId[connection.id],
                partner = partnerProfilesByUserId[
                    partnerUserId(connection.userAId, connection.userBId, userId)
                ],
                now = snapshot.now
            )
        }

        val hasPendingSchedulingConnection = snapshot.pendingSchedulingConnections.isNotEmpty()

        val activeInteractionsSummary = HomeActiveInteractionsSummaryResponse(
            activeInitialCount = pendingActions.size,
            activeConnectionCount = nextSteps.count { it.type != HomeNextStepType.SECOND_CHAT_EXPIRED },
            hasPendingSchedulingConnection = hasPendingSchedulingConnection,
            actionableConnectionCount = nextSteps.count { it.type != HomeNextStepType.SECOND_CHAT_EXPIRED }
        )

        val matchmakingAvailability = matchmakingAvailabilityService.availabilityFor(
            userId = userId,
            inQueue = inQueue
        )

        return HomeProjection(
            home = HomeResponse(
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
            ),
            nextRefreshAt = nextHiddenHomeTransitionAt(
                snapshot = snapshot,
                currentUserId = userId
            )
        )
    }

    @Transactional
    fun getPendingHomeState(userId: UUID): HomePendingStateResponse =
        readMetrics.recordHomeLoad(ReadMetrics.HOME_VARIANT_PENDING) {
            getPendingHomeStateMeasured(userId)
        }

    private fun getPendingHomeStateMeasured(userId: UUID): HomePendingStateResponse {
        val status = homeStatusService.getOrCreateStatus(userId = userId)
        val snapshot = loadHomeOperationalSnapshot(userId)
        val hasPendingSchedulingConnection = snapshot.pendingSchedulingConnections.isNotEmpty()

        return HomePendingStateResponse(
            version = status.version,
            pendingActions = snapshot.activeMatches.mapNotNull { match ->
                toPendingActionLite(
                    match = match,
                    currentUserId = userId,
                    firstChat = snapshot.firstChatsByMatchId[match.id],
                    decision = snapshot.chatDecisionsByMatchId[match.id],
                    visualReview = snapshot.visualReviewByMatchId[match.id],
                    now = snapshot.now
                )
            },
            nextSteps = snapshot.visibleConnections.mapNotNull { connection ->
                toNextStepLite(
                    connection = connection,
                    secondChat = snapshot.secondChatsByConnectionId[connection.id],
                    secondChatAvailableAt = snapshot.confirmedNegotiationsByConnectionId[
                        connection.id
                    ]?.confirmedDateTime,
                    myAttendanceStatus = snapshot.myAttendanceStatusByConnectionId[connection.id],
                    now = snapshot.now
                )
            },
            passiveNotices = passiveNoticesForPendingScheduling(
                hasPendingSchedulingConnection
            ),
            serverTime = snapshot.now
        )
    }

    private fun loadHomeOperationalSnapshot(userId: UUID): HomeOperationalSnapshot {
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

        val eligibleMatches = candidateMatches
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

        val firstChatsByMatchId = if (eligibleMatches.isEmpty()) {
            emptyMap()
        } else {
            chatRepository
                .findByMatchIdInAndChatType(
                    matchIds = eligibleMatches.map { it.id },
                    chatType = ChatType.FIRST_CHAT
                )
                .associateBy { it.matchId }
        }

        val chatDecisionsByMatchId = loadChatDecisionsByMatchId(eligibleMatches)
        val activeMatches =
            orderActiveMatches(
                matches = eligibleMatches,
                visualReviewByMatchId = visualReviewByMatchId,
                firstChatsByMatchId = firstChatsByMatchId
            )

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

        val historicalClosedConnections = connectionRepository
            .findRecentClosedConfirmedSecondChatConnectionsWithoutChat(
                userId = userId,
                confirmedAfter = now.minusMinutes(
                    entryWindowMinutes + secondChatReadOnlyRetentionMinutes
                )
            )
            .filterNot { it.counterpartIdFor(userId) in blockedCounterpartIds }

        val visibleConnections = filterDismissedConnections(
            userId = userId,
            connections = (activeConnections + historicalClosedConnections).distinctBy { it.id }
        )

        val pendingSchedulingConnections = filterDismissedConnections(
            userId = userId,
            connections = connectionRepository
                .findByParticipantIdAndStateIn(
                    userId = userId,
                    states = listOf(ConnectionState.SCHEDULING_PENDING)
                )
                .filterNot { it.counterpartIdFor(userId) in blockedCounterpartIds }
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

        val myAttendanceStatusByConnectionId = if (visibleConnections.isEmpty()) {
            emptyMap()
        } else {
            participationRepository
                .findByConnectionIdIn(visibleConnections.map { it.id })
                .filter { it.userId == userId }
                .associate { it.connectionId to it.attendanceStatus }
        }

        return HomeOperationalSnapshot(
            now = now,
            blockedCounterpartIds = blockedCounterpartIds,
            activeMatches = activeMatches,
            visualReviewByMatchId = visualReviewByMatchId,
            firstChatsByMatchId = firstChatsByMatchId,
            chatDecisionsByMatchId = chatDecisionsByMatchId,
            visibleConnections = orderVisibleConnections(
                connections = visibleConnections,
                secondChatsByConnectionId = secondChatsByConnectionId,
                confirmedNegotiationsByConnectionId = confirmedNegotiationsByConnectionId,
                myAttendanceStatusByConnectionId = myAttendanceStatusByConnectionId,
                now = now
            ),
            pendingSchedulingConnections = pendingSchedulingConnections,
            secondChatsByConnectionId = secondChatsByConnectionId,
            confirmedNegotiationsByConnectionId = confirmedNegotiationsByConnectionId,
            myAttendanceStatusByConnectionId = myAttendanceStatusByConnectionId
        )
    }

    private fun loadChatDecisionsByMatchId(
        activeMatches: List<Match>
    ): Map<UUID, ChatDecision?> {
        val activeChatMatchIds = activeMatches
            .filter { it.state == MatchState.CHAT_ACTIVE }
            .map { it.id }

        if (activeChatMatchIds.isEmpty()) {
            return emptyMap()
        }

        val foundDecisionsByMatchId =
            chatDecisionRepository.findByMatchIdIn(activeChatMatchIds)
                .associateBy { it.matchId }

        return activeChatMatchIds.associateWith { foundDecisionsByMatchId[it] }
    }

    private fun filterDismissedConnections(
        userId: UUID,
        connections: List<Connection>
    ): List<Connection> {
        val dismissedConnectionIds =
            if (connections.isEmpty()) {
                emptySet()
            } else {
                dismissalRepository
                    .findDismissedConnectionIds(
                        userId = userId,
                        connectionIds = connections.map { it.id }
                    )
                    .toSet()
            }

        return connections.filter { it.id !in dismissedConnectionIds }
    }

    private fun orderVisibleConnections(
        connections: List<Connection>,
        secondChatsByConnectionId: Map<UUID, Chat>,
        confirmedNegotiationsByConnectionId: Map<UUID, ScheduleNegotiation>,
        myAttendanceStatusByConnectionId: Map<UUID, SecondChatAttendanceStatus>,
        now: OffsetDateTime
    ): List<Connection> =
        connections.sortedWith { leftConnection, rightConnection ->
            compareHomeConnectionOrder(
                left = homeConnectionOrderKey(
                    connection = leftConnection,
                    secondChat = secondChatsByConnectionId[leftConnection.id],
                    confirmedDateTime = confirmedNegotiationsByConnectionId[leftConnection.id]?.confirmedDateTime,
                    myAttendanceStatus = myAttendanceStatusByConnectionId[leftConnection.id],
                    now = now
                ),
                right = homeConnectionOrderKey(
                    connection = rightConnection,
                    secondChat = secondChatsByConnectionId[rightConnection.id],
                    confirmedDateTime = confirmedNegotiationsByConnectionId[rightConnection.id]?.confirmedDateTime,
                    myAttendanceStatus = myAttendanceStatusByConnectionId[rightConnection.id],
                    now = now
                )
            )
        }

    private fun orderActiveMatches(
        matches: List<Match>,
        visualReviewByMatchId: Map<UUID, VisualReview>,
        firstChatsByMatchId: Map<UUID, Chat>
    ): List<Match> =
        matches.sortedWith { leftMatch, rightMatch ->
            val leftKey =
                homePendingActionOrderKey(
                    match = leftMatch,
                    visualReview = visualReviewByMatchId[leftMatch.id],
                    firstChat = firstChatsByMatchId[leftMatch.id]
                )
            val rightKey =
                homePendingActionOrderKey(
                    match = rightMatch,
                    visualReview = visualReviewByMatchId[rightMatch.id],
                    firstChat = firstChatsByMatchId[rightMatch.id]
                )
            val dueAtComparison = leftKey.dueAt.compareNullableTo(rightKey.dueAt)
            if (dueAtComparison != 0) {
                dueAtComparison
            } else {
                leftKey.matchId.compareTo(rightKey.matchId)
            }
        }

    private fun homePendingActionOrderKey(
        match: Match,
        visualReview: VisualReview?,
        firstChat: Chat?
    ): HomePendingActionOrderKey {
        val dueAt =
            when (match.state) {
                MatchState.VISUAL_PHASE -> visualReview?.expiresAt
                MatchState.CHAT_ACTIVE -> firstChat?.timeoutAt
                else -> match.updatedAt
            }

        return HomePendingActionOrderKey(
            dueAt = dueAt,
            matchId = match.id
        )
    }

    private fun compareHomeConnectionOrder(
        left: HomeConnectionOrderKey,
        right: HomeConnectionOrderKey
    ): Int {
        val categoryComparison = left.category.compareTo(right.category)
        if (categoryComparison != 0) {
            return categoryComparison
        }

        if (left.category == HOME_ORDER_CURRENT_SECOND_CHAT) {
            val availableAtComparison = left.timestamp.compareNullableDescendingTo(right.timestamp)
            if (availableAtComparison != 0) {
                return availableAtComparison
            }
            return left.connectionId.compareTo(right.connectionId)
        }

        val timestampComparison = left.timestamp.compareNullableTo(right.timestamp)
        if (timestampComparison != 0) {
            return timestampComparison
        }
        return left.connectionId.compareTo(right.connectionId)
    }

    private fun homeConnectionOrderKey(
        connection: Connection,
        secondChat: Chat?,
        confirmedDateTime: OffsetDateTime?,
        myAttendanceStatus: SecondChatAttendanceStatus?,
        now: OffsetDateTime
    ): HomeConnectionOrderKey {
        val category =
            when (connection.state) {
                ConnectionState.SECOND_CHAT_AVAILABLE ->
                    if (isSecondChatEntryExpiredForCurrentUser(confirmedDateTime, myAttendanceStatus, now)) {
                        HOME_ORDER_EXPIRED_SECOND_CHAT
                    } else {
                        HOME_ORDER_CURRENT_SECOND_CHAT
                    }
                ConnectionState.SECOND_CHAT ->
                    when (secondChatNextStepType(secondChat, confirmedDateTime, myAttendanceStatus, now)) {
                        HomeNextStepType.SECOND_CHAT_AVAILABLE -> HOME_ORDER_CURRENT_SECOND_CHAT
                        HomeNextStepType.SECOND_CHAT_EXPIRED -> HOME_ORDER_EXPIRED_SECOND_CHAT
                        HomeNextStepType.SECOND_CHAT_READ_ONLY -> HOME_ORDER_READ_ONLY_SECOND_CHAT
                        else -> HOME_ORDER_OTHER
                    }
                ConnectionState.SECOND_CHAT_SCHEDULED ->
                    when (scheduledSecondChatNextStepType(confirmedDateTime, myAttendanceStatus, now)) {
                        HomeNextStepType.SECOND_CHAT_AVAILABLE -> HOME_ORDER_CURRENT_SECOND_CHAT
                        HomeNextStepType.SECOND_CHAT_EXPIRED -> HOME_ORDER_EXPIRED_SECOND_CHAT
                        HomeNextStepType.SECOND_CHAT_SCHEDULED -> HOME_ORDER_SCHEDULED_SECOND_CHAT
                        else -> HOME_ORDER_OTHER
                    }
                ConnectionState.SCHEDULING_PHASE -> HOME_ORDER_SCHEDULING_PHASE
                ConnectionState.CLOSED ->
                    if (closedZeroAttendanceSecondChatExpiredType(
                            secondChat = secondChat,
                            confirmedDateTime = confirmedDateTime,
                            myAttendanceStatus = myAttendanceStatus,
                            now = now
                        ) != null
                    ) {
                        HOME_ORDER_EXPIRED_SECOND_CHAT
                    } else {
                        HOME_ORDER_OTHER
                    }
                ConnectionState.SCHEDULING_PENDING -> HOME_ORDER_OTHER
            }

        val timestamp =
            when (category) {
                HOME_ORDER_CURRENT_SECOND_CHAT,
                HOME_ORDER_SCHEDULED_SECOND_CHAT -> secondChat?.availableAt ?: confirmedDateTime
                HOME_ORDER_EXPIRED_SECOND_CHAT -> recentExpiredSecondChatUntil(confirmedDateTime)
                HOME_ORDER_SCHEDULING_PHASE -> connection.schedulingExpiresAt
                HOME_ORDER_READ_ONLY_SECOND_CHAT -> secondChat?.readOnlyUntil
                    ?: secondChat?.endedAt
                    ?: secondChat?.timeoutAt
                    ?: secondChat?.availableAt
                    ?: confirmedDateTime
                else -> connection.updatedAt
            }

        return HomeConnectionOrderKey(
            category = category,
            timestamp = timestamp,
            connectionId = connection.id
        )
    }

    private fun OffsetDateTime?.compareNullableTo(other: OffsetDateTime?): Int =
        when {
            this == null && other == null -> 0
            this == null -> 1
            other == null -> -1
            else -> compareTo(other)
        }

    private fun OffsetDateTime?.compareNullableDescendingTo(other: OffsetDateTime?): Int =
        when {
            this == null && other == null -> 0
            this == null -> 1
            other == null -> -1
            else -> other.compareTo(this)
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
    ): Boolean {
        if (match.state != MatchState.VISUAL_PHASE || visualReview == null) {
            return false
        }

        return !visualReview.availableAt.isAfter(now) &&
            visualReview.expiresAt?.isAfter(now) == true &&
            visualReview.hasPendingDecisionFor(
                userId = currentUserId,
                userAId = match.userAId,
                userBId = match.userBId
            )
    }

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
                visualStartedAt = null,
                visualExpiresAt = null,
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
                visualStartedAt = visualReview?.availableAt?.toInstant(),
                visualExpiresAt = visualReview?.expiresAt?.toInstant(),
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
                chatId = actionableFirstChat.id,
                visualStartedAt = null,
                visualExpiresAt = null
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
                chatId = null,
                visualStartedAt = visualReview?.availableAt?.toInstant(),
                visualExpiresAt = visualReview?.expiresAt?.toInstant()
            )
        }

        return null
    }

    private fun toNextStep(
        connection: Connection,
        secondChat: Chat?,
        secondChatAvailableAt: OffsetDateTime?,
        myAttendanceStatus: SecondChatAttendanceStatus?,
        partner: Profile?,
        now: OffsetDateTime
    ): HomeNextStepResponse? {
        val type = when (connection.state) {
            ConnectionState.SCHEDULING_PHASE -> HomeNextStepType.SCHEDULING
            ConnectionState.SECOND_CHAT_SCHEDULED -> scheduledSecondChatNextStepType(
                confirmedDateTime = secondChatAvailableAt,
                myAttendanceStatus = myAttendanceStatus,
                now = now
            ) ?: return null
            ConnectionState.SECOND_CHAT_AVAILABLE -> availableSecondChatNextStepType(
                confirmedDateTime = secondChatAvailableAt,
                myAttendanceStatus = myAttendanceStatus,
                now = now
            ) ?: return null
            ConnectionState.SECOND_CHAT -> secondChatNextStepType(
                secondChat = secondChat,
                confirmedDateTime = secondChatAvailableAt,
                myAttendanceStatus = myAttendanceStatus,
                now = now
            ) ?: return null
            ConnectionState.SCHEDULING_PENDING,
            ConnectionState.CLOSED -> closedZeroAttendanceSecondChatExpiredType(
                secondChat = secondChat,
                confirmedDateTime = secondChatAvailableAt,
                myAttendanceStatus = myAttendanceStatus,
                now = now
            ) ?: return null
        }

        return HomeNextStepResponse(
            type = type,
            connectionId = connection.id,
            matchId = connection.matchId,
            partner = partner?.let { PartnerSummaryResponse.from(it) },
            secondChat = secondChatResponse(
                chat = secondChat,
                availableAt = secondChatAvailableAt,
                myAttendanceStatus = myAttendanceStatus,
                partner = partner
            )
        )
    }

    private fun toNextStepLite(
        connection: Connection,
        secondChat: Chat?,
        secondChatAvailableAt: OffsetDateTime?,
        myAttendanceStatus: SecondChatAttendanceStatus?,
        now: OffsetDateTime
    ): HomeNextStepLiteResponse? {
        val type = when (connection.state) {
            ConnectionState.SCHEDULING_PHASE -> HomeNextStepType.SCHEDULING
            ConnectionState.SECOND_CHAT_SCHEDULED -> scheduledSecondChatNextStepType(
                confirmedDateTime = secondChatAvailableAt,
                myAttendanceStatus = myAttendanceStatus,
                now = now
            ) ?: return null
            ConnectionState.SECOND_CHAT_AVAILABLE -> availableSecondChatNextStepType(
                confirmedDateTime = secondChatAvailableAt,
                myAttendanceStatus = myAttendanceStatus,
                now = now
            ) ?: return null
            ConnectionState.SECOND_CHAT -> secondChatNextStepType(
                secondChat = secondChat,
                confirmedDateTime = secondChatAvailableAt,
                myAttendanceStatus = myAttendanceStatus,
                now = now
            ) ?: return null
            ConnectionState.SCHEDULING_PENDING,
            ConnectionState.CLOSED -> closedZeroAttendanceSecondChatExpiredType(
                secondChat = secondChat,
                confirmedDateTime = secondChatAvailableAt,
                myAttendanceStatus = myAttendanceStatus,
                now = now
            ) ?: return null
        }

        return HomeNextStepLiteResponse(
            type = type,
            connectionId = connection.id,
            matchId = connection.matchId,
            secondChat = secondChatLiteResponse(
                chat = secondChat,
                availableAt = secondChatAvailableAt,
                myAttendanceStatus = myAttendanceStatus
            )
        )
    }

    private fun scheduledSecondChatNextStepType(
        confirmedDateTime: OffsetDateTime?,
        myAttendanceStatus: SecondChatAttendanceStatus?,
        now: OffsetDateTime
    ): HomeNextStepType? {
        confirmedDateTime ?: return HomeNextStepType.SECOND_CHAT_SCHEDULED
        if (isSecondChatEntryExpiredForCurrentUser(confirmedDateTime, myAttendanceStatus, now)) {
            return if (isRecentExpiredSecondChatVisible(confirmedDateTime, now)) {
                HomeNextStepType.SECOND_CHAT_EXPIRED
            } else {
                null
            }
        }

        return if (now.isBefore(confirmedDateTime)) {
            HomeNextStepType.SECOND_CHAT_SCHEDULED
        } else {
            HomeNextStepType.SECOND_CHAT_AVAILABLE
        }
    }

    private fun availableSecondChatNextStepType(
        confirmedDateTime: OffsetDateTime?,
        myAttendanceStatus: SecondChatAttendanceStatus?,
        now: OffsetDateTime
    ): HomeNextStepType? =
        if (isSecondChatEntryExpiredForCurrentUser(confirmedDateTime, myAttendanceStatus, now)) {
            if (isRecentExpiredSecondChatVisible(confirmedDateTime, now)) {
                HomeNextStepType.SECOND_CHAT_EXPIRED
            } else {
                null
            }
        } else {
            HomeNextStepType.SECOND_CHAT_AVAILABLE
        }

    private fun closedZeroAttendanceSecondChatExpiredType(
        secondChat: Chat?,
        confirmedDateTime: OffsetDateTime?,
        myAttendanceStatus: SecondChatAttendanceStatus?,
        now: OffsetDateTime
    ): HomeNextStepType? =
        if (
            secondChat == null &&
            !hasJoinedSecondChat(myAttendanceStatus) &&
            myAttendanceStatus == SecondChatAttendanceStatus.NO_SHOW &&
            isRecentExpiredSecondChatVisible(confirmedDateTime, now)
        ) {
            HomeNextStepType.SECOND_CHAT_EXPIRED
        } else {
            null
        }
    private fun isSecondChatEntryExpiredForCurrentUser(
        confirmedDateTime: OffsetDateTime?,
        myAttendanceStatus: SecondChatAttendanceStatus?,
        now: OffsetDateTime
    ): Boolean {
        val entryClosesAt = confirmedDateTime?.plusMinutes(entryWindowMinutes)
            ?: return false

        return !hasJoinedSecondChat(myAttendanceStatus) &&
            !entryClosesAt.isAfter(now)
    }

    private fun secondChatResponse(
        chat: Chat?,
        availableAt: OffsetDateTime?,
        myAttendanceStatus: SecondChatAttendanceStatus?,
        partner: Profile?
    ): HomeChatResponse? {
        val resolvedAvailableAt = availableAt ?: chat?.availableAt ?: return null
        val expiresAt = chat?.timeoutAt ?: resolvedAvailableAt.plusMinutes(secondChatDurationMinutes)
        val resolvedAttendanceStatus = myAttendanceStatus ?: SecondChatAttendanceStatus.PENDING

        return HomeChatResponse.from(
            chat = chat,
            availableAt = resolvedAvailableAt,
            entryClosesAt = resolvedAvailableAt.plusMinutes(entryWindowMinutes),
            expiresAt = expiresAt,
            readOnlyUntil = chat?.readOnlyUntil,
            durationMinutes = secondChatDurationMinutes,
            myAttendanceStatus = resolvedAttendanceStatus,
            partner = partner
        )
    }

    private fun secondChatLiteResponse(
        chat: Chat?,
        availableAt: OffsetDateTime?,
        myAttendanceStatus: SecondChatAttendanceStatus?
    ): HomePendingSecondChatLiteResponse? {
        val resolvedAvailableAt = availableAt ?: chat?.availableAt ?: return null
        val expiresAt = chat?.timeoutAt ?: resolvedAvailableAt.plusMinutes(secondChatDurationMinutes)

        return HomePendingSecondChatLiteResponse(
            chatId = chat?.id,
            availableAt = resolvedAvailableAt,
            entryClosesAt = resolvedAvailableAt.plusMinutes(entryWindowMinutes),
            expiresAt = expiresAt,
            readOnlyUntil = chat?.readOnlyUntil,
            durationMinutes = secondChatDurationMinutes,
            myAttendanceStatus = myAttendanceStatus ?: SecondChatAttendanceStatus.PENDING
        )
    }

    private fun secondChatNextStepType(
        secondChat: Chat?,
        confirmedDateTime: OffsetDateTime?,
        myAttendanceStatus: SecondChatAttendanceStatus?,
        now: OffsetDateTime
    ): HomeNextStepType? {
        if (isSecondChatEntryExpiredForCurrentUser(confirmedDateTime, myAttendanceStatus, now)) {
            return if (isRecentExpiredSecondChatVisible(confirmedDateTime, now)) {
                HomeNextStepType.SECOND_CHAT_EXPIRED
            } else {
                null
            }
        }

        return when (secondChat?.status) {
            ChatStatus.FINISHED,
            ChatStatus.EXPIRED,
            ChatStatus.ABANDONED ->
                if (secondChat.readOnlyUntil?.isAfter(now) == true) {
                    HomeNextStepType.SECOND_CHAT_READ_ONLY
                } else {
                    null
                }

            ChatStatus.CLOSED -> null
            else -> HomeNextStepType.SECOND_CHAT_AVAILABLE
        }
    }

    private fun hasJoinedSecondChat(status: SecondChatAttendanceStatus?): Boolean =
        status == SecondChatAttendanceStatus.ON_TIME ||
            status == SecondChatAttendanceStatus.LATE

    private fun isRecentExpiredSecondChatVisible(
        confirmedDateTime: OffsetDateTime?,
        now: OffsetDateTime
    ): Boolean =
        recentExpiredSecondChatUntil(confirmedDateTime)?.isAfter(now) == true

    private fun recentExpiredSecondChatUntil(
        confirmedDateTime: OffsetDateTime?
    ): OffsetDateTime? =
        confirmedDateTime
            ?.plusMinutes(entryWindowMinutes)
            ?.plusMinutes(secondChatReadOnlyRetentionMinutes)

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

    private fun nextHiddenHomeTransitionAt(
        snapshot: HomeOperationalSnapshot,
        currentUserId: UUID
    ): OffsetDateTime? =
        snapshot.activeMatches
            .asSequence()
            .filter { it.state == MatchState.VISUAL_PHASE }
            .mapNotNull { match ->
                val review = snapshot.visualReviewByMatchId[match.id] ?: return@mapNotNull null
                if (
                    review.availableAt.isAfter(snapshot.now) &&
                    review.expiresAt?.isAfter(snapshot.now) == true &&
                    review.hasPendingDecisionFor(
                        userId = currentUserId,
                        userAId = match.userAId,
                        userBId = match.userBId
                    )
                ) {
                    review.availableAt
                } else {
                    null
                }
            }
            .minOrNull()

            .let { visualRefreshAt ->
                val secondChatRefreshAt = snapshot.visibleConnections
                    .asSequence()
                    .flatMap { connection ->
                        secondChatRefreshBoundaries(
                            connection = connection,
                            secondChat = snapshot.secondChatsByConnectionId[connection.id],
                            confirmedDateTime = snapshot.confirmedNegotiationsByConnectionId[
                                connection.id
                            ]?.confirmedDateTime,
                            myAttendanceStatus = snapshot.myAttendanceStatusByConnectionId[
                                connection.id
                            ],
                            now = snapshot.now
                        )
                    }
                    .filter { it.isAfter(snapshot.now) }
                    .minOrNull()

                listOfNotNull(visualRefreshAt, secondChatRefreshAt).minOrNull()
            }

    private fun secondChatRefreshBoundaries(
        connection: Connection,
        secondChat: Chat?,
        confirmedDateTime: OffsetDateTime?,
        myAttendanceStatus: SecondChatAttendanceStatus?,
        now: OffsetDateTime
    ): Sequence<OffsetDateTime> {
        confirmedDateTime ?: return emptySequence()

        val entryClosesAt = confirmedDateTime.plusMinutes(entryWindowMinutes)
        val recentUntil = entryClosesAt.plusMinutes(secondChatReadOnlyRetentionMinutes)
        val type = when (connection.state) {
            ConnectionState.SECOND_CHAT_SCHEDULED -> scheduledSecondChatNextStepType(
                confirmedDateTime = confirmedDateTime,
                myAttendanceStatus = myAttendanceStatus,
                now = now
            )
            ConnectionState.SECOND_CHAT_AVAILABLE -> availableSecondChatNextStepType(
                confirmedDateTime = confirmedDateTime,
                myAttendanceStatus = myAttendanceStatus,
                now = now
            )
            ConnectionState.SECOND_CHAT -> secondChatNextStepType(
                secondChat = secondChat,
                confirmedDateTime = confirmedDateTime,
                myAttendanceStatus = myAttendanceStatus,
                now = now
            )
            ConnectionState.CLOSED -> closedZeroAttendanceSecondChatExpiredType(
                secondChat = secondChat,
                confirmedDateTime = confirmedDateTime,
                myAttendanceStatus = myAttendanceStatus,
                now = now
            )
            ConnectionState.SCHEDULING_PENDING,
            ConnectionState.SCHEDULING_PHASE -> null
        }

        return sequence {
            if (
                connection.state == ConnectionState.SECOND_CHAT_SCHEDULED &&
                now.isBefore(confirmedDateTime)
            ) {
                yield(confirmedDateTime)
            }
            if (
                !hasJoinedSecondChat(myAttendanceStatus) &&
                now.isBefore(entryClosesAt)
            ) {
                yield(entryClosesAt)
            }
            if (
                type == HomeNextStepType.SECOND_CHAT_EXPIRED &&
                now.isBefore(recentUntil)
            ) {
                yield(recentUntil)
            }
        }
    }

    private data class HomeConnectionOrderKey(
        val category: Int,
        val timestamp: OffsetDateTime?,
        val connectionId: UUID
    )

    private data class HomePendingActionOrderKey(
        val dueAt: OffsetDateTime?,
        val matchId: UUID
    )

    private companion object {
        const val HOME_ORDER_CURRENT_SECOND_CHAT = 0
        const val HOME_ORDER_SCHEDULED_SECOND_CHAT = 1
        const val HOME_ORDER_SCHEDULING_PHASE = 2
        const val HOME_ORDER_READ_ONLY_SECOND_CHAT = 3
        const val HOME_ORDER_EXPIRED_SECOND_CHAT = 4
        const val HOME_ORDER_OTHER = 5
    }
}

private fun Match.counterpartIdFor(userId: UUID): UUID =
    if (userAId == userId) userBId else userAId

private fun Connection.counterpartIdFor(userId: UUID): UUID =
    if (userAId == userId) userBId else userAId

