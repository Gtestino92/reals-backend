package com.reals.backend.controller.dto

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.domain.SecondChatAttendanceStatus
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class HomeResponse(
    val profileStatus: ProfileStatus?,
    val matchmaking: HomeMatchmakingResponse,
    val activeInteractionsSummary: HomeActiveInteractionsSummaryResponse,
    val pendingActions: List<HomePendingActionResponse>,
    val nextSteps: List<HomeNextStepResponse>,
    val passiveNotices: List<HomePassiveNoticeResponse>
)

data class HomeStatusResponse(
    val version: Long,
    val dirty: Boolean,
    val nextRefreshAt: OffsetDateTime?,
    val serverTime: OffsetDateTime
)

data class HomePendingStateResponse(
    val version: Long,
    val pendingActions: List<HomePendingActionLiteResponse>,
    val nextSteps: List<HomeNextStepLiteResponse>,
    val passiveNotices: List<HomePassiveNoticeResponse>,
    val serverTime: OffsetDateTime
)

data class HomeActiveInteractionsSummaryResponse(
    val activeInitialCount: Int,
    val activeConnectionCount: Int,
    val hasPendingSchedulingConnection: Boolean,
    val actionableConnectionCount: Int
)

data class HomeMatchmakingResponse(
    val inQueue: Boolean,
    val canSearch: Boolean,
    val blockedReason: HomeMatchmakingBlockedReasonResponse?
)

data class HomeMatchmakingBlockedReasonResponse(
    val code: String,
    val message: String,
    val nextAvailableAt: OffsetDateTime? = null
)

enum class HomePendingActionType {
    FIRST_CHAT,
    VISUAL_REVIEW
}

data class HomePendingActionResponse(
    val type: HomePendingActionType,
    val matchId: UUID,
    val chatId: UUID?,
    val visualStartedAt: Instant?,
    val visualExpiresAt: Instant?,
    val partner: PartnerSummaryResponse?
)

data class HomePendingActionLiteResponse(
    val type: HomePendingActionType,
    val matchId: UUID,
    val chatId: UUID?,
    val visualStartedAt: Instant?,
    val visualExpiresAt: Instant?
)

enum class HomeNextStepType {
    SCHEDULING,
    SECOND_CHAT_SCHEDULED,
    SECOND_CHAT_AVAILABLE,
    SECOND_CHAT_EXPIRED,
    SECOND_CHAT_READ_ONLY
}

data class HomeNextStepResponse(
    val type: HomeNextStepType,
    val connectionId: UUID,
    val matchId: UUID,
    val partner: PartnerSummaryResponse?,
    val createdAt: OffsetDateTime? = null,
    val schedulingExpiresAt: OffsetDateTime? = null,
    val secondChat: HomeChatResponse? = null,
    val requiresAction: Boolean
)

data class HomeNextStepLiteResponse(
    val type: HomeNextStepType,
    val connectionId: UUID,
    val matchId: UUID,
    val createdAt: OffsetDateTime? = null,
    val schedulingExpiresAt: OffsetDateTime? = null,
    val secondChat: HomePendingSecondChatLiteResponse? = null,
    val requiresAction: Boolean
)

data class HomePendingSecondChatLiteResponse(
    val chatId: UUID?,
    val availableAt: OffsetDateTime?,
    val entryClosesAt: OffsetDateTime?,
    val expiresAt: OffsetDateTime?,
    val readOnlyUntil: OffsetDateTime?,
    val durationMinutes: Long?,
    val myAttendanceStatus: SecondChatAttendanceStatus?
)

enum class HomePassiveNoticeType {
    SCHEDULING_PREPARING
}

data class HomePassiveNoticeResponse(
    val type: HomePassiveNoticeType
)

data class HomeChatResponse(
    val chatId: UUID?,
    val chatType: ChatType?,
    val chatStatus: ChatStatus?,
    val availableAt: OffsetDateTime,
    val entryClosesAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val readOnlyUntil: OffsetDateTime?,
    val durationMinutes: Long,
    val myAttendanceStatus: SecondChatAttendanceStatus,
    val partner: PartnerSummaryResponse?
) {
    companion object {
        fun from(
            chat: Chat?,
            availableAt: OffsetDateTime,
            entryClosesAt: OffsetDateTime,
            expiresAt: OffsetDateTime,
            readOnlyUntil: OffsetDateTime?,
            durationMinutes: Long,
            myAttendanceStatus: SecondChatAttendanceStatus,
            partner: Profile?
        ) = HomeChatResponse(
            chatId = chat?.id,
            chatType = chat?.chatType,
            chatStatus = chat?.status,
            availableAt = availableAt,
            entryClosesAt = entryClosesAt,
            expiresAt = expiresAt,
            readOnlyUntil = readOnlyUntil,
            durationMinutes = durationMinutes,
            myAttendanceStatus = myAttendanceStatus,
            partner = partner?.let { PartnerSummaryResponse.from(it) }
        )
    }
}
