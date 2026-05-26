package com.reals.backend.repository

import com.reals.backend.domain.*
import org.springframework.data.jpa.repository.JpaRepository
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
}
