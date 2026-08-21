package com.reals.backend.service.matching

import com.reals.backend.config.MatchmakingProperties
import com.reals.backend.repository.VisualReviewRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class VisualAdvancementCapService(
    private val visualReviewRepository: VisualReviewRepository,
    private val matchmakingProperties: MatchmakingProperties
) {

    @Transactional(readOnly = true)
    fun statusFor(
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): VisualAdvancementCapStatus {
        val limit = matchmakingProperties.visualAdvancement.maxPerWindow
        val windowHours = matchmakingProperties.visualAdvancement.windowHours
        val cutoff = now.minusHours(windowHours)
        val activeCount = visualReviewRepository.countAdvancementsForUserCreatedAfter(
            userId = userId,
            cutoff = cutoff
        )

        if (activeCount < limit) {
            return VisualAdvancementCapStatus(
                blocked = false,
                nextAvailableAt = null
            )
        }

        val thresholdAdvancement = visualReviewRepository.findRetryThresholdAdvancementCreatedAfter(
            userId = userId,
            cutoff = cutoff,
            offset = limit - 1
        )

        return VisualAdvancementCapStatus(
            blocked = true,
            nextAvailableAt = thresholdAdvancement?.plusHours(windowHours)
        )
    }

    fun isBlocked(
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean =
        statusFor(userId = userId, now = now).blocked
}

data class VisualAdvancementCapStatus(
    val blocked: Boolean,
    val nextAvailableAt: OffsetDateTime?
)
