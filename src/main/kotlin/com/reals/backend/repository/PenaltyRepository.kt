package com.reals.backend.repository

import com.reals.backend.domain.Penalty
import org.springframework.data.jpa.repository.*
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface PenaltyRepository :
    JpaRepository<Penalty, UUID> {

    fun existsByUserIdAndActiveTrue(
        userId: UUID
    ): Boolean

    fun findByUserIdAndActiveTrue(
        userId: UUID
    ): List<Penalty>

    @Query("SELECT p from Penalty p where p.active=true and p.expiresAt <= :now")
    fun findExpiredActivePenalties(
        @Param("now")
        now: OffsetDateTime
    ): List<Penalty>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Penalty p set p.expiresAt = :expiresAt where p.id = :penaltyId")
    fun updateExpiresAt(
        @Param("penaltyId") penaltyId: UUID,
        @Param("expiresAt") expiresAt: OffsetDateTime
    ): Int
}
