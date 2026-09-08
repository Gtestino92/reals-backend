package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.EnqueueMatchmakingRequest
import com.reals.backend.controller.dto.QueueStatusResponse
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.service.LegalComplianceService
import com.reals.backend.service.matching.MatchmakingService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/matchmaking")
class MatchmakingController(
    private val matchmakingService: MatchmakingService,
    private val queueRepository: MatchmakingQueueRepository,
    private val legalComplianceService: LegalComplianceService
) {

    /**
     * Add user to matchmaking queue
     * Preconditions: profile ACTIVE, no effective account ban, match limit not reached
     */
    @PostMapping("/queue")
    fun enqueue(
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: EnqueueMatchmakingRequest
    ): ResponseEntity<QueueStatusResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        matchmakingService.enqueue(
            userId = userId,
            latitude = request.latitude,
            longitude = request.longitude,
            accuracyMeters = request.accuracyMeters
        )
        return ResponseEntity.ok(QueueStatusResponse(userId = userId, inQueue = true))
    }

    /**
     * Removes a user from the queue
     * Safe to call even if the user is not in the queue
     */
    @DeleteMapping("/queue")
    fun dequeue(
        @CurrentUserId userId: UUID
    ): ResponseEntity<QueueStatusResponse> {
        matchmakingService.dequeue(
            userId = userId
        )
        return ResponseEntity.ok(QueueStatusResponse(userId = userId, inQueue = false))
    }

    /**
     * Returns whether a user is currently in the matchmaking queue
     */
    @GetMapping("/queue")
    fun queueStatus(
        @CurrentUserId userId: UUID
    ): ResponseEntity<QueueStatusResponse> {
        val inQueue = queueRepository.existsByUserId(
            userId = userId
        )
        return ResponseEntity.ok(
            QueueStatusResponse(
                userId = userId,
                inQueue = inQueue
            )
        )
    }

}
