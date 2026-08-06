package com.reals.backend.service

import java.util.UUID

data class MatchFoundEvent(
    val matchId: UUID,
    val chatId: UUID
)
