package com.reals.backend.repository

import com.reals.backend.domain.ConnectionHomeDismissal
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ConnectionHomeDismissalRepository : JpaRepository<ConnectionHomeDismissal, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ConnectionHomeDismissal d where d.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: UUID): Int

    fun findByUserIdAndConnectionId(
        userId: UUID,
        connectionId: UUID
    ): ConnectionHomeDismissal?

    fun existsByUserIdAndConnectionId(
        userId: UUID,
        connectionId: UUID
    ): Boolean

    @Query(
        """
        select d.connectionId from ConnectionHomeDismissal d
        where d.userId = :userId
          and d.connectionId in :connectionIds
        """
    )
    fun findDismissedConnectionIds(
        @Param("userId") userId: UUID,
        @Param("connectionIds") connectionIds: Collection<UUID>
    ): List<UUID>
}
