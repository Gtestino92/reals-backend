package com.reals.backend.repository

import com.reals.backend.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ActiveEngagementLockRepository :
    JpaRepository<ActiveEngagementLock, UUID> {

    fun countByUserIdAndEngagementType(
        userId: UUID,
        engagementType: EngagementType
    ): Int

    fun deleteByEngagementId(
        engagementId: UUID
    )

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
