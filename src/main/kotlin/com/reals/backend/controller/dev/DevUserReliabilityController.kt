package com.reals.backend.controller.dev

import com.reals.backend.domain.UserReliabilityDimension
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.service.UserService
import com.reals.backend.service.reliability.UserReliabilityScoreService
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

@RestController
@Profile("local", "local-nodb", "local-postgres", "local-firebase")
@RequestMapping("/api/local-dev/user-reliability")
class DevUserReliabilityController(
    private val userService: UserService,
    private val userReliabilityScoreService: UserReliabilityScoreService
) {

    @GetMapping("/{userId}")
    fun getUserReliability(
        @PathVariable userId: UUID
    ): ResponseEntity<DevUserReliabilityScoreResponse> {
        userService.findByIdOrThrow(userId)

        return ResponseEntity.ok(
            DevUserReliabilityScoreResponse.from(
                userReliabilityScoreService.scoreBreakdown(userId)
            )
        )
    }
}

data class DevUserReliabilityScoreResponse(
    val userId: UUID,
    val enabled: Boolean,
    val baseScore: Int,
    val weightedDelta: Double,
    val effectiveScore: Double,
    val events: List<DevUserReliabilityEventResponse>
) {
    companion object {
        fun from(breakdown: UserReliabilityScoreService.ScoreBreakdown): DevUserReliabilityScoreResponse =
            DevUserReliabilityScoreResponse(
                userId = breakdown.userId,
                enabled = breakdown.enabled,
                baseScore = breakdown.baseScore,
                weightedDelta = breakdown.weightedDelta,
                effectiveScore = breakdown.effectiveScore,
                events = breakdown.events.map { DevUserReliabilityEventResponse.from(it) }
            )
    }
}

data class DevUserReliabilityEventResponse(
    val id: UUID,
    val eventType: UserReliabilityEventType,
    val dimension: UserReliabilityDimension,
    val delta: Int,
    val temporalWeight: Double,
    val effectiveDelta: Double,
    val occurredAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val relatedMatchId: UUID?,
    val relatedChatId: UUID?,
    val relatedConnectionId: UUID?,
    val relatedSafetyReportId: UUID?,
    val metadata: Map<String, Any?> = emptyMap()
) {
    companion object {
        fun from(breakdown: UserReliabilityScoreService.EventBreakdown): DevUserReliabilityEventResponse {
            val event = breakdown.event
            return DevUserReliabilityEventResponse(
                id = event.id,
                eventType = event.eventType,
                dimension = event.dimension,
                delta = event.delta,
                temporalWeight = breakdown.temporalWeight,
                effectiveDelta = breakdown.effectiveDelta,
                occurredAt = event.occurredAt,
                expiresAt = event.expiresAt,
                relatedMatchId = event.relatedMatchId,
                relatedChatId = event.relatedChatId,
                relatedConnectionId = event.relatedConnectionId,
                relatedSafetyReportId = event.relatedSafetyReportId
            )
        }
    }
}
