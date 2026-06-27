package com.reals.backend.service

import com.reals.backend.domain.EngagementType
import com.reals.backend.domain.QueueStatus
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

data class MatchmakingDiagnostics(
    val queueWaitingCount: Long,
    val queueTotalCount: Long,
    val activeMatchLocks: Long,
    val activeConnectionLocks: Long,
    val oldestQueueEntryEnteredAt: OffsetDateTime?,
    val oldestActiveLockCreatedAt: OffsetDateTime?
)

@Service
class MatchmakingDiagnosticsService(
    private val matchmakingQueueRepository: MatchmakingQueueRepository,
    private val activeEngagementLockRepository: ActiveEngagementLockRepository
) {

    @Transactional(readOnly = true)
    fun getDiagnostics(): MatchmakingDiagnostics =
        MatchmakingDiagnostics(
            queueWaitingCount = matchmakingQueueRepository.countByStatus(QueueStatus.WAITING),
            queueTotalCount = matchmakingQueueRepository.count(),
            activeMatchLocks = activeEngagementLockRepository.countByEngagementType(EngagementType.MATCH),
            activeConnectionLocks = activeEngagementLockRepository.countByEngagementType(EngagementType.CONNECTION),
            oldestQueueEntryEnteredAt = matchmakingQueueRepository
                .findFirstByStatusOrderByEnteredAtAsc(QueueStatus.WAITING)
                ?.enteredAt,
            oldestActiveLockCreatedAt = activeEngagementLockRepository.findOldestCreatedAt()
        )
}
