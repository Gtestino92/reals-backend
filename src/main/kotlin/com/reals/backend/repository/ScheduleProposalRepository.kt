package com.reals.backend.repository

import com.reals.backend.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ScheduleProposalRepository :
    JpaRepository<ScheduleProposal, UUID> {

    fun findByConnectionId(
        connectionId: UUID
    ): List<ScheduleProposal>

    fun findByConnectionIdAndUserId(
        connectionId: UUID,
        userId: UUID
    ): List<ScheduleProposal>

    fun findByConnectionIdAndStatus(
        connectionId: UUID,
        status: ProposalStatus
    ): List<ScheduleProposal>

    fun existsByConnectionIdAndUserId(
        connectionId: UUID,
        userId: UUID
    ): Boolean

    fun deleteByConnectionId(connectionId: UUID)
}
