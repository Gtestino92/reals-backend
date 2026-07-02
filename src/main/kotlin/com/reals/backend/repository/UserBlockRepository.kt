package com.reals.backend.repository

import com.reals.backend.domain.UserBlock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserBlockRepository : JpaRepository<UserBlock, UUID> {
    fun findByBlockerUserIdAndBlockedUserId(
        blockerUserId: UUID,
        blockedUserId: UUID
    ): UserBlock?

    fun existsByBlockerUserIdAndBlockedUserId(
        blockerUserId: UUID,
        blockedUserId: UUID
    ): Boolean

    @Query(
        """
        select case when count(ub) > 0 then true else false end
        from UserBlock ub
        where (ub.blockerUserId = :userAId and ub.blockedUserId = :userBId)
           or (ub.blockerUserId = :userBId and ub.blockedUserId = :userAId)
        """
    )
    fun existsBetweenUsers(
        @Param("userAId") userAId: UUID,
        @Param("userBId") userBId: UUID
    ): Boolean
}
