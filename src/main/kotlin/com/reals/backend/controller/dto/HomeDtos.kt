package com.reals.backend.controller.dto

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.ProfileStatus
import java.util.UUID

data class HomeResponse(
    val profileStatus: ProfileStatus?,
    val queue: HomeQueueResponse,
    val activeMatches: List<HomeMatchResponse>,
    val activeConnections: List<HomeConnectionResponse>
)

data class HomeQueueResponse(
    val inQueue: Boolean
)

data class HomeMatchResponse(
    val matchId: UUID,
    val matchState: MatchState,
    val firstChat: HomeChatResponse?
) {
    companion object {
        fun from(
            match: Match,
            firstChat: Chat?
        ) = HomeMatchResponse(
            matchId = match.id,
            matchState = match.state,
            firstChat = firstChat?.let { HomeChatResponse.from(it) }
        )
    }
}

data class HomeConnectionResponse(
    val connectionId: UUID,
    val matchId: UUID,
    val connectionState: ConnectionState,
    val secondChat: HomeChatResponse?
) {
    companion object {
        fun from(
            connection: Connection,
            secondChat: Chat?
        ) = HomeConnectionResponse(
            connectionId = connection.id,
            matchId = connection.matchId,
            connectionState = connection.state,
            secondChat = secondChat?.let { HomeChatResponse.from(it) }
        )
    }
}

data class HomeChatResponse(
    val chatId: UUID,
    val chatType: ChatType,
    val chatStatus: ChatStatus
) {
    companion object {
        fun from(chat: Chat) = HomeChatResponse(
            chatId = chat.id,
            chatType = chat.chatType,
            chatStatus = chat.status
        )
    }
}
