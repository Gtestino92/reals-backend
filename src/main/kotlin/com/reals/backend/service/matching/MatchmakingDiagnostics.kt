package com.reals.backend.service.matching

import java.time.OffsetDateTime

data class MatchmakingDiagnostics(
    val queueWaitingCount: Long,
    val queueTotalCount: Long,
    val activeMatchLocks: Long,
    val activeConnectionLocks: Long,
    val oldestQueueEntryEnteredAt: OffsetDateTime?,
    val oldestActiveLockCreatedAt: OffsetDateTime?
)
