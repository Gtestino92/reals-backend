package com.reals.backend.controller

import com.reals.backend.config.CurrentUserId
import com.reals.backend.controller.dto.MatchResponse
import com.reals.backend.controller.dto.ProcessQueueResponse
import com.reals.backend.controller.dto.QueueStatusResponse
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.service.ChatService
import com.reals.backend.service.MatchService
import com.reals.backend.service.MatchmakingService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/matchmaking")
class MatchmakingController(
    private val matchmakingService: MatchmakingService,
    private val matchService: MatchService,
    private val chatService: ChatService,
    private val queueRepository: MatchmakingQueueRepository
) {

    /**
     * Add user to matchmaking queue
     * Preconditions: profile ACTIVE, no active penalty, match limit not reached
     */
    @PostMapping("/enqueue")
    fun enqueue(
        @CurrentUserId userId: UUID
    ): ResponseEntity<QueueStatusResponse> {
        matchmakingService.enqueue(
            userId = userId
        )
        return ResponseEntity.ok(QueueStatusResponse(userId = userId, inQueue = true))
    }

    /**
     * Removes a user from the queue
     * Safe to call even if the user is not in the queue
     */
    @DeleteMapping("/dequeue")
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
    @GetMapping("/status")
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

    /**
     * TESTING ONLY Manually triggers the matchmaking worker for up to [batchSize] pairs
     * In prod this would be driven by a scheduler or a dedicated worker
     * For each pair found creates a Match + ActiveEngagementLocs + starts first ChatSession
     */
    @PostMapping("/process")
    fun processQueue(
        @RequestParam(defaultValue = "5")
        batchSize: Int
    ): ResponseEntity<ProcessQueueResponse> {

        val pairs = matchmakingService.findCandidatePairs(
            batchSize = batchSize
        )

        val matches = pairs.map { (userAId, userBId) ->

            val match = matchService.createMatch(
                userAId = userAId,
                userBId = userBId
            )

            chatService.startFirstChat(
                matchId = match.id
            )

            MatchResponse.from(match)
        }

        return ResponseEntity.ok(
            ProcessQueueResponse(
                matchesCreated = matches.size,
                pairs = matches
            )
        )
    }
}