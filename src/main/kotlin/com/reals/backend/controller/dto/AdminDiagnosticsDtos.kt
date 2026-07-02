package com.reals.backend.controller.dto

import com.reals.backend.service.matching.MatchmakingDiagnostics
import java.time.OffsetDateTime

data class MatchmakingDiagnosticsResponse(
    val queueWaitingCount: Long,
    val queueTotalCount: Long,
    val activeMatchLocks: Long,
    val activeConnectionLocks: Long,
    val oldestQueueEntryEnteredAt: OffsetDateTime?,
    val oldestActiveLockCreatedAt: OffsetDateTime?
) {
    companion object {
        fun from(diagnostics: MatchmakingDiagnostics): MatchmakingDiagnosticsResponse =
            MatchmakingDiagnosticsResponse(
                queueWaitingCount = diagnostics.queueWaitingCount,
                queueTotalCount = diagnostics.queueTotalCount,
                activeMatchLocks = diagnostics.activeMatchLocks,
                activeConnectionLocks = diagnostics.activeConnectionLocks,
                oldestQueueEntryEnteredAt = diagnostics.oldestQueueEntryEnteredAt,
                oldestActiveLockCreatedAt = diagnostics.oldestActiveLockCreatedAt
            )
    }
}
