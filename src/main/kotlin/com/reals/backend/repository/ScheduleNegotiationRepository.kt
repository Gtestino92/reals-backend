package com.reals.backend.repository

import com.reals.backend.domain.ScheduleNegotiation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ScheduleNegotiationRepository :
    JpaRepository<ScheduleNegotiation, UUID> {

    fun findByConnectionId(
        connectionId: UUID
    ): ScheduleNegotiation?
}
