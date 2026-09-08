package com.reals.backend.controller.dto

import com.reals.backend.service.SchedulingConflictService
import java.time.OffsetDateTime

data class SchedulingAvailabilityResponse(
    val conflictWindowMinutes: Long,
    val unavailableWindows: List<SchedulingUnavailableWindowResponse>,
    val serverTime: OffsetDateTime
) {
    companion object {
        fun from(snapshot: SchedulingConflictService.SchedulingAvailabilitySnapshot) =
            SchedulingAvailabilityResponse(
                conflictWindowMinutes = snapshot.conflictWindowMinutes,
                unavailableWindows = snapshot.unavailableWindows.map {
                    SchedulingUnavailableWindowResponse(
                        startsAt = it.startsAt,
                        endsAt = it.endsAt
                    )
                },
                serverTime = snapshot.serverTime
            )
    }
}

data class SchedulingUnavailableWindowResponse(
    val startsAt: OffsetDateTime,
    val endsAt: OffsetDateTime
)
