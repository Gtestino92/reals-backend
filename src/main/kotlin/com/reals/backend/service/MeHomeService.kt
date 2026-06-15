package com.reals.backend.service

import com.reals.backend.controller.dto.HomeConnectionResponse
import com.reals.backend.controller.dto.HomeMatchResponse
import com.reals.backend.controller.dto.HomeQueueResponse
import com.reals.backend.controller.dto.HomeResponse
import com.reals.backend.domain.ChatDecision
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
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
    private val chatDecisionRepository: ChatDecisionRepository
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

        return HomeResponse(
            profileStatus = profileStatus,
            queue = HomeQueueResponse(
                inQueue = inQueue
            ),
            activeMatches = activeMatches.map { match ->
                val firstChat = firstChatsByMatchId[match.id]
                    ?.takeIf {
                        match.state == MatchState.CHAT_ACTIVE &&
                                hasPendingFirstChatDecisionForCurrentUser(
                                    match = match,
                                    currentUserId = userId,
                                    decision = chatDecisionsByMatchId[match.id],
                                )
                    }

                HomeMatchResponse.from(
                    match = match,
                    firstChat = firstChat,
                    partner = partnerProfilesByUserId[
                        partnerUserId(match.userAId, match.userBId, userId)
                    ]
                )
            },
            activeConnections = activeConnections.map { connection ->
                HomeConnectionResponse.from(
                    connection = connection,
                    secondChat = secondChatsByConnectionId[connection.id],
                    partner = partnerProfilesByUserId[
                        partnerUserId(connection.userAId, connection.userBId, userId)
                    ]
                )
            }
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
}

