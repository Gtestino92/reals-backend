package com.reals.backend.repository

import com.reals.backend.domain.MatchmakingQueueEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface MatchmakingQueueRepository :
    JpaRepository<MatchmakingQueueEntry, UUID> {

    fun existsByUserId(userId: UUID): Boolean

    fun deleteByUserId(userId: UUID)

    @Query(
        value = "SELECT * FROM matchmaking_queue WHERE status='WAITING' ORDER BY entered_at, id LIMIT :limit FOR UPDATE SKIP LOCKED",
        nativeQuery = true
    )
    fun findWaitingSkipLocked(
        @Param("limit")
        limit:Int
    ): List<MatchmakingQueueEntry>
}
