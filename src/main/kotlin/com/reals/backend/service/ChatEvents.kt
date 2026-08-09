package com.reals.backend.service

import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import java.util.UUID

data class FirstChatTerminatedEvent(
    val matchId: UUID,
    val chatId: UUID,
    val finalStatus: ChatStatus,
    val endedReason: ChatEndReason
)
