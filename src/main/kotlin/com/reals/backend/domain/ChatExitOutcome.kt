package com.reals.backend.domain

import java.util.UUID

data class ChatExitOutcome(
    val chat: Chat,
    val exitRequest: ChatExitRequest,
    val penaltyApplied: Boolean,
    val penalizedUserId: UUID?
)
