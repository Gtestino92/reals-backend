package com.reals.backend.service

import com.reals.backend.domain.UserBlockSource
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.util.UUID

@Service
@Transactional
class UserBlockCommandService(
    private val userBlockService: UserBlockService,
    private val containmentService: PairInteractionContainmentService
) {
    fun blockUserAndContain(
        blockerUserId: UUID,
        blockedUserId: UUID,
        source: UserBlockSource,
        sourceReportId: UUID? = null
    ): UserBlockCreationResult {
        val result = userBlockService.blockUserWithResult(
            blockerUserId, blockedUserId, source, sourceReportId
        )
        containmentService.containPair(
            userAId = blockerUserId,
            userBId = blockedUserId,
            cause = PairInteractionContainmentCause.USER_BLOCK
        )
        return result
    }
}
