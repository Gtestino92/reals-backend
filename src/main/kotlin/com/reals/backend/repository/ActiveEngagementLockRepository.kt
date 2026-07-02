package com.reals.backend.repository

import com.reals.backend.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface ActiveEngagementLockRepository :
    JpaRepository<ActiveEngagementLock, UUID> {

    fun countByUserIdAndEngagementType(
        userId: UUID,
        engagementType: EngagementType
    ): Int

    fun countByEngagementType(
        engagementType: EngagementType
    ): Long

    @Query("SELECT MIN(activeLock.createdAt) FROM ActiveEngagementLock activeLock")
    fun findOldestCreatedAt(): OffsetDateTime?

    fun deleteByEngagementId(
        engagementId: UUID
    )

    fun existsByUserIdAndEngagementIdAndEngagementType(
        userId: UUID,
        engagementId: UUID,
        engagementType: EngagementType
    ): Boolean

    @Modifying
    @Query(
        value = """
        DELETE FROM active_engagement_locks
        WHERE user_id = :userId
          AND engagement_id = :engagementId
    """,
        nativeQuery = true
    )
    fun deleteByUserIdAndEngagementId(
        @Param("userId") userId: UUID,
        @Param("engagementId") engagementId: UUID
    ): Int

    @Modifying
    @Query(
        value = """
        DELETE FROM active_engagement_locks
        WHERE user_id = :userId
    """,
        nativeQuery = true
    )
    fun deleteByUserId(
        @Param("userId") userId: UUID
    ): Int
}
