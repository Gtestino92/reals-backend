package com.reals.backend.controller.dto

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfileStatus
import java.time.OffsetDateTime
import java.util.UUID

data class HomeResponse(
    val profileStatus: ProfileStatus?,
    val engagementSummary: HomeEngagementSummaryResponse,
    val queue: HomeQueueResponse,
    val activeMatches: List<HomeMatchResponse>,
    val activeConnections: List<HomeConnectionResponse>,
    val matchmaking: HomeMatchmakingResponse,
    val pendingActions: List<HomePendingActionResponse>,
    val nextSteps: List<HomeNextStepResponse>,
    val passiveNotices: List<HomePassiveNoticeResponse>
)

data class HomeEngagementSummaryResponse(
    val activeMatchCount: Int,
    val activeConnectionCount: Int,
    val pendingSchedulingConnectionCount: Int,
    val actionableConnectionCount: Int
)

data class HomeQueueResponse(
    val inQueue: Boolean
)

data class HomeMatchmakingResponse(
    val inQueue: Boolean,
    val canSearch: Boolean,
    val blockedReason: HomeMatchmakingBlockedReasonResponse?
)

data class HomeMatchmakingBlockedReasonResponse(
    val code: String,
    val message: String
)

enum class HomePendingActionType {
    FIRST_CHAT,
    VISUAL_REVIEW
}

data class HomePendingActionResponse(
    val type: HomePendingActionType,
    val matchId: UUID,
    val chatId: UUID?,
    val partner: PartnerSummaryResponse?
)

enum class HomeNextStepType {
    SCHEDULING,
    SECOND_CHAT_SCHEDULED,
    SECOND_CHAT_AVAILABLE
}

data class HomeNextStepResponse(
    val type: HomeNextStepType,
    val connectionId: UUID,
    val matchId: UUID,
    val partner: PartnerSummaryResponse?,
    val secondChat: HomeChatResponse? = null
)

enum class HomePassiveNoticeType {
    SCHEDULING_PREPARING
}

data class HomePassiveNoticeResponse(
    val type: HomePassiveNoticeType,
    val count: Int
)

data class HomeMatchResponse(
    val matchId: UUID,
    val matchState: MatchState,
    val firstChat: HomeChatResponse?,
    val partner: PartnerSummaryResponse?
) {
    companion object {
        fun from(
            match: Match,
            firstChat: Chat?,
            partner: Profile?
        ) = HomeMatchResponse(
            matchId = match.id,
            matchState = match.state,
            firstChat = firstChat?.let { HomeChatResponse.from(it, partner) },
            partner = partner?.let { PartnerSummaryResponse.from(it) }
        )
    }
}

data class HomeConnectionResponse(
    val connectionId: UUID,
    val matchId: UUID,
    val connectionState: ConnectionState,
    val secondChat: HomeChatResponse?,
    val partner: PartnerSummaryResponse?
) {
    companion object {
        fun from(
            connection: Connection,
            secondChat: Chat?,
            partner: Profile?
        ) = HomeConnectionResponse(
            connectionId = connection.id,
            matchId = connection.matchId,
            connectionState = connection.state,
            secondChat = secondChat?.let { HomeChatResponse.from(it, partner) },
            partner = partner?.let { PartnerSummaryResponse.from(it) }
        )
    }
}

data class HomeChatResponse(
    val chatId: UUID,
    val chatType: ChatType,
    val chatStatus: ChatStatus,
    val expiresAt: OffsetDateTime,
    val partner: PartnerSummaryResponse?
) {
    companion object {
        fun from(
            chat: Chat,
            partner: Profile?
        ) = HomeChatResponse(
            chatId = chat.id,
            chatType = chat.chatType,
            chatStatus = chat.status,
            expiresAt = chat.timeoutAt,
            partner = partner?.let { PartnerSummaryResponse.from(it) }
        )
    }
}
