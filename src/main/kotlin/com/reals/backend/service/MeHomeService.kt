package com.reals.backend.service

import com.reals.backend.controller.dto.HomeConnectionResponse
import com.reals.backend.controller.dto.HomeMatchResponse
import com.reals.backend.controller.dto.HomeQueueResponse
import com.reals.backend.controller.dto.HomeResponse
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.MatchState
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.ProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MeHomeService(
    private val profileRepository: ProfileRepository,
    private val queueRepository: MatchmakingQueueRepository,
    private val matchRepository: MatchRepository,
    private val chatRepository: ChatRepository,
    private val connectionRepository: ConnectionRepository
) {

    @Transactional(readOnly = true)
    fun getHome(userId: UUID): HomeResponse {
        val profileStatus = profileRepository.findByUserId(userId)?.status
        val inQueue = queueRepository.existsByUserId(userId)

        val activeMatches = matchRepository
            .findByParticipantIdAndStateIn(
                userId = userId,
                states = listOf(
                    MatchState.CHAT_ACTIVE,
                    MatchState.VISUAL_PHASE
                )
            )
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

        return HomeResponse(
            profileStatus = profileStatus,
            queue = HomeQueueResponse(
                inQueue = inQueue
            ),
            activeMatches = activeMatches.map { match ->
                HomeMatchResponse.from(
                    match = match,
                    firstChat = firstChatsByMatchId[match.id]
                )
            },
            activeConnections = activeConnections.map { connection ->
                HomeConnectionResponse.from(
                    connection = connection,
                    secondChat = secondChatsByConnectionId[connection.id]
                )
            }
        )
    }
}
