package com.reals.backend.repository

import com.reals.backend.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

data class SubmittedRound(val connectionId: UUID, val roundNumber: Int)

interface ScheduleProposalRepository :
    JpaRepository<ScheduleProposal, UUID> {

    @Query("""
        select distinct new com.reals.backend.repository.SubmittedRound(p.connectionId, p.roundNumber)
        from ScheduleProposal p
        where p.userId = :userId and p.connectionId in :connectionIds
    """)
    fun submittedRounds(userId: UUID, connectionIds: List<UUID>): List<SubmittedRound>

    fun findByConnectionId(
        connectionId: UUID
    ): List<ScheduleProposal>

    fun findByConnectionIdAndRoundNumber(
        connectionId: UUID,
        roundNumber: Int
    ): List<ScheduleProposal>

    fun existsByConnectionIdAndUserIdAndRoundNumber(
        connectionId: UUID,
        userId: UUID,
        roundNumber: Int
    ): Boolean
}
