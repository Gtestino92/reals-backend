package com.reals.backend.controller.dev

import com.reals.backend.controller.dto.MatchResponse
import com.reals.backend.controller.dto.ProcessQueueResponse
import com.reals.backend.service.MatchmakingProcessorService
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("local", "local-nodb", "local-postgres", "dev")
@RequestMapping("/api/dev/matchmaking")
class DevMatchmakingController(
    private val matchmakingProcessorService: MatchmakingProcessorService
) {

    /**
     * Manually triggers matchmaking for local/dev testing.
     * Production should use a worker/scheduler rather than exposing this to users.
     */
    @PostMapping("/process")
    fun processQueue(
        @RequestParam(defaultValue = "5")
        batchSize: Int
    ): ResponseEntity<ProcessQueueResponse> {
        val result = matchmakingProcessorService.processBatch(
            batchSize = batchSize
        )

        return ResponseEntity.ok(
            ProcessQueueResponse(
                matchesCreated = result.matchesCreated,
                candidatePairs = result.candidatePairs,
                failedPairs = result.failedPairs,
                pairs = result.matches.map { MatchResponse.from(it) }
            )
        )
    }
}
