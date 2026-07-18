package com.reals.backend.service

import java.util.UUID

data class SchedulingProposalsReceivedEvent(
    val connectionId: UUID,
    val triggeringUserId: UUID,
    val recipientUserId: UUID,
    val roundNumber: Int
)

data class SchedulingConfirmedEvent(
    val connectionId: UUID,
    val triggeringUserId: UUID
)
