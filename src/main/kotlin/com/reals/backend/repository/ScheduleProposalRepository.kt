package com.reals.backend.repository

import com.reals.backend.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ScheduleProposalRepository :
    JpaRepository<ScheduleProposal, UUID> {

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
