package com.reals.backend.repository

import com.reals.backend.domain.VisualReview
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface VisualReviewRepository :
    JpaRepository<VisualReview, UUID> {

    fun findByMatchId(
        matchId: UUID
    ): VisualReview?

    fun findByMatchIdIn(matchIds: Collection<UUID>): List<VisualReview>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VisualReview v where v.matchId = :matchId")
    fun findByMatchIdForUpdate(
        @Param("matchId") matchId: UUID
    ): VisualReview?

    fun findByExpiresAtBefore(
        expiresAt: OffsetDateTime
    ): List<VisualReview>

    @Query(
        """
        select v.matchId from VisualReview v
        where v.expiresAt <= :expiresAt
        order by v.expiresAt asc, v.id asc
        """
    )
    fun findExpiredMatchIds(
        @Param("expiresAt") expiresAt: OffsetDateTime,
        pageable: Pageable
    ): List<UUID>

    @Query(
        """
        select v
        from VisualReview v
        where v.reminderEligibleAt is not null
          and v.reminderEligibleAt <= :now
          and v.availableAt <= :now
          and v.expiresAt is not null
          and v.expiresAt > :now
          and (
            v.userAVisualDecision is null
            or v.userBVisualDecision is null
          )
        """
    )
    fun findVisualReviewReminderCandidates(
        @Param("now") now: OffsetDateTime
    ): List<VisualReview>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update VisualReview v set v.expiresAt = :expiresAt where v.matchId = :matchId")
    fun updateExpiresAtByMatchId(
        @Param("matchId") matchId: UUID,
        @Param("expiresAt") expiresAt: OffsetDateTime
    ): Int
}
