package com.reals.backend.repository

import com.reals.backend.domain.VisualReview
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime
import java.util.UUID

interface VisualReviewRepository :
    JpaRepository<VisualReview, UUID> {

    fun findByMatchId(
        matchId: UUID
    ): VisualReview?

    fun findByExpiresAtBefore(
        expiresAt: OffsetDateTime
    ): List<VisualReview>
}
