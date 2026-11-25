package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.SchedulingAvailabilityResponse
import com.reals.backend.service.ConnectionService
import com.reals.backend.service.SchedulingConflictService
import com.reals.backend.service.SchedulingSnapshotService
import com.reals.backend.controller.dto.SchedulingSnapshotResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/connections")
class SchedulingAvailabilityController(
    private val connectionService: ConnectionService,
    private val schedulingSnapshotService: SchedulingSnapshotService,
    private val schedulingConflictService: SchedulingConflictService
) {

    @GetMapping("/{connectionId}/scheduling-snapshot")
    fun getSchedulingSnapshot(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<SchedulingSnapshotResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(schedulingSnapshotService.read(connectionId, userId))

    @GetMapping("/{connectionId}/scheduling-availability")
    fun getSchedulingAvailability(
        @CurrentUserId userId: UUID,
        @PathVariable connectionId: UUID
    ): ResponseEntity<SchedulingAvailabilityResponse> {
        connectionService.findByIdForUserOrThrow(
            connectionId = connectionId,
            userId = userId
        )

        return ResponseEntity.ok(
            SchedulingAvailabilityResponse.from(
                schedulingConflictService.availabilityFor(
                    userId = userId,
                    excludedConnectionId = connectionId
                )
            )
        )
    }
}
