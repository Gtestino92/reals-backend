package com.reals.backend.controller.dev

import com.reals.backend.controller.dto.MatchResponse
import com.reals.backend.controller.dto.ProcessQueueResponse
import com.reals.backend.service.ChatService
import com.reals.backend.service.MatchService
import com.reals.backend.service.MatchmakingService
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("local", "local-nodb", "dev")
@RequestMapping("/api/dev/matchmaking")
class DevMatchmakingController(
    private val matchmakingService: MatchmakingService,
    private val matchService: MatchService,
    private val chatService: ChatService
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
