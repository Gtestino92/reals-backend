package com.reals.backend.controller.dto

import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.VisualDecision
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.*

data class MatchResponse(
    val id: UUID,
    val userAId: UUID,
    val userBId: UUID,
    val state: MatchState,
    val connectionId: UUID?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
) {
    companion object {
        fun from(
            match: Match,
            connectionId: UUID? = null
        ) = MatchResponse(
            id = match.id,
            userAId = match.userAId,
            userBId = match.userBId,
            state = match.state,
            connectionId = connectionId,
            createdAt = match.createdAt,
            updatedAt = match.updatedAt
        )
    }
}

data class VisualDecisionRequest(
    val decision: VisualDecision
)

data class PersonalMessageRequest(
    @field:NotBlank
    @field:Size(max = 280)
    @field:Pattern(regexp = "^[^\\p{Cntrl}]*$")
    val message: String
)

data class ProcessQueueResponse(
    val matchesCreated: Int,
    val candidatePairs: Int,
    val failedPairs: Int,
    val pairs: List<MatchResponse>
)

data class QueueStatusResponse(
    val userId: UUID,
    val inQueue: Boolean
)

data class EnqueueMatchmakingRequest(
    @field:DecimalMin("-90.0")
    @field:DecimalMax("90.0")
    val latitude: Double,

    @field:DecimalMin("-180.0")
    @field:DecimalMax("180.0")
    val longitude: Double,

    @field:Min(0)
    @field:Max(100000)
    val accuracyMeters: Int? = null
)

data class ChatDecisionRequest(
    val decision: ChatContinueDecision
)
