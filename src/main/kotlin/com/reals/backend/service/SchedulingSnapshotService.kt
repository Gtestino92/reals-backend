package com.reals.backend.service

import com.reals.backend.controller.dto.ConnectionResponse
import com.reals.backend.controller.dto.NegotiationResponse
import com.reals.backend.controller.dto.ScheduleProposalResponse
import com.reals.backend.controller.dto.SchedulingAvailabilityResponse
import com.reals.backend.controller.dto.SchedulingSnapshotResponse
import com.reals.backend.domain.ConnectionState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class SchedulingSnapshotService(
    private val connectionService: ConnectionService,
    private val schedulingService: SchedulingService,
    private val conflictService: SchedulingConflictService,
    private val clock: Clock
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(connectionId: UUID, userId: UUID): SchedulingSnapshotResponse {
        val connection = connectionService.findByIdForUserOrThrow(connectionId, userId)
        val now = OffsetDateTime.now(clock)
        val detail = ConnectionResponse.from(connection)
        if (connection.state == ConnectionState.SCHEDULING_PENDING) {
            return SchedulingSnapshotResponse(detail, null, emptyList(), null, now)
        }
        val negotiation = schedulingService.findNegotiationOrThrow(connectionId)
        return SchedulingSnapshotResponse(
            connection = detail,
            negotiation = NegotiationResponse.from(negotiation, connection.schedulingExpiresAt),
            proposals = schedulingService.getProposals(connectionId).map { ScheduleProposalResponse.from(it) },
            availability = SchedulingAvailabilityResponse.from(conflictService.availabilityFor(userId, connectionId, now)),
            serverTime = now
        )
    }
}
