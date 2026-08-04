package com.reals.backend.repository

import com.reals.backend.domain.VisualReviewAffinityIndicator
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface VisualReviewAffinityIndicatorRepository : JpaRepository<VisualReviewAffinityIndicator, UUID> {
    fun findByMatchIdOrderByOrdinal(matchId: UUID): List<VisualReviewAffinityIndicator>

    fun countByMatchId(matchId: UUID): Long
}
