package com.reals.backend.repository

import com.reals.backend.domain.Penalty
import org.springframework.data.jpa.repository.*
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface PenaltyRepository :
    JpaRepository<Penalty, UUID> {

    @Query(
        """
        SELECT p FROM Penalty p
        WHERE p.userId = :userId
          AND p.active = true
          AND (
            p.type = com.reals.backend.domain.PenaltyType.PERMANENT_BAN
            OR (
              p.type = com.reals.backend.domain.PenaltyType.TEMPORARY_BAN
              AND p.expiresAt IS NOT NULL
              AND p.expiresAt > :now
            )
          )
        """
    )
    fun findEffectiveBans(
        @Param("userId")
        userId: UUID,
        @Param("now")
        now: OffsetDateTime
    ): List<Penalty>

    @Query(
        """
        SELECT p from Penalty p
        where p.active=true
            and p.type = com.reals.backend.domain.PenaltyType.TEMPORARY_BAN
            and p.expiresAt is not null
            and p.expiresAt <= :now
        """
    )
    fun findExpiredActivePenalties(
        @Param("now")
        now: OffsetDateTime
    ): List<Penalty>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE Penalty p
        SET p.active = false
        WHERE p.id = :penaltyId
          AND p.active = true
          AND p.type = com.reals.backend.domain.PenaltyType.TEMPORARY_BAN
          AND p.expiresAt is not null
          AND p.expiresAt <= :now
        """
    )
    fun deactivateExpiredActivePenalty(
        @Param("penaltyId")
        penaltyId: UUID,
        @Param("now")
        now: OffsetDateTime
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Penalty p set p.expiresAt = :expiresAt where p.id = :penaltyId")
    fun updateExpiresAt(
        @Param("penaltyId") penaltyId: UUID,
        @Param("expiresAt") expiresAt: OffsetDateTime
    ): Int
}
