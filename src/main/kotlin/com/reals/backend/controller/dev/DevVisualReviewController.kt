package com.reals.backend.controller.dev

import com.reals.backend.service.VisualReviewService
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

@RestController
@Profile("local-nodb", "local-postgres", "local-firebase", "dev")
@RequestMapping("/api/local-dev/matches")
class DevVisualReviewController(
    private val visualReviewService: VisualReviewService
) {

    @PostMapping("/{matchId}/visual-review/make-available-now")
    fun makeVisualReviewAvailableNow(
        @PathVariable matchId: UUID
    ): ResponseEntity<DevVisualReviewAvailabilityResponse> {
        val review = visualReviewService.makeAvailableNowForLocalDev(matchId)
        return ResponseEntity.ok(
            DevVisualReviewAvailabilityResponse(
                target = "visual-review-availability",
                id = matchId,
                availableAt = review.availableAt,
                expiresAt = review.expiresAt
            )
        )
    }
}

data class DevVisualReviewAvailabilityResponse(
    val target: String,
    val id: UUID,
    val availableAt: OffsetDateTime,
    val expiresAt: OffsetDateTime?
)
