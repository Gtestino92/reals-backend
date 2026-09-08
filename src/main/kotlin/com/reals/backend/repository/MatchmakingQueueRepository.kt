package com.reals.backend.repository

import com.reals.backend.domain.MatchmakingQueueEntry
import com.reals.backend.domain.QueueStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface MatchmakingQueueRepository :
    JpaRepository<MatchmakingQueueEntry, UUID> {

    fun existsByUserId(userId: UUID): Boolean

    fun findByUserId(userId: UUID): MatchmakingQueueEntry?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MatchmakingQueueEntry q where q.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: UUID): Int

    fun countByStatus(status: QueueStatus): Long

    fun findFirstByStatusOrderByEnteredAtAsc(status: QueueStatus): MatchmakingQueueEntry?
}
