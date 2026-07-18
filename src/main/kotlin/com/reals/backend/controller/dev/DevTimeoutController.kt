package com.reals.backend.controller.dev

import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.VisualReviewRepository
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

@RestController
@Profile("local-nodb", "local-postgres", "local-firebase", "dev")
@RequestMapping("/api/local-dev/timeouts")
class DevTimeoutController(
    private val chatRepository: ChatRepository,
    private val connectionRepository: ConnectionRepository,
    private val penaltyRepository: PenaltyRepository,
    private val scheduleNegotiationRepository: ScheduleNegotiationRepository,
    private val visualReviewRepository: VisualReviewRepository
) {

    @PostMapping("/chats/{chatId}/expire-now")
    @Transactional
    fun expireChatNow(
        @PathVariable chatId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val expiresAt = OffsetDateTime.now().minusSeconds(1)
        requireUpdated(
            updated = chatRepository.updateTimeoutAt(chatId, expiresAt),
            message = "Chat not found: $chatId"
        )
        return ResponseEntity.ok(
            DevTimeoutMutationResponse(
                target = "chat",
                id = chatId,
                expiresAt = expiresAt
            )
        )
    }

    @PostMapping("/chats/{chatId}/read-only-expire-now")
    @Transactional
    fun expireChatReadOnlyNow(
        @PathVariable chatId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val expiresAt = OffsetDateTime.now().minusSeconds(1)
        requireUpdated(
            updated = chatRepository.updateReadOnlyUntil(chatId, expiresAt),
            message = "Chat not found: $chatId"
        )
        return ResponseEntity.ok(
            DevTimeoutMutationResponse(
                target = "chat-read-only",
                id = chatId,
                expiresAt = expiresAt
            )
        )
    }

    @PostMapping("/matches/{matchId}/visual-expire-now")
    @Transactional
    fun expireVisualPhaseNow(
        @PathVariable matchId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val expiresAt = OffsetDateTime.now().minusSeconds(1)
        requireUpdated(
            updated = visualReviewRepository.updateExpiresAtByMatchId(matchId, expiresAt),
            message = "Visual review not found for match: $matchId"
        )
        return ResponseEntity.ok(
            DevTimeoutMutationResponse(
                target = "visual-review",
                id = matchId,
                expiresAt = expiresAt
            )
        )
    }

    @PostMapping("/connections/{connectionId}/scheduling-expire-now")
    @Transactional
    fun expireSchedulingNow(
        @PathVariable connectionId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val expiresAt = OffsetDateTime.now().minusSeconds(1)
        requireUpdated(
            updated = connectionRepository.updateSchedulingExpiresAt(connectionId, expiresAt),
            message = "Connection not found: $connectionId"
        )
        return ResponseEntity.ok(
            DevTimeoutMutationResponse(
                target = "connection",
                id = connectionId,
                expiresAt = expiresAt
            )
        )
    }

    @PostMapping("/connections/{connectionId}/scheduling-available-now")
    @Transactional
    fun makeSchedulingAvailableNow(
        @PathVariable connectionId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val availableAt = OffsetDateTime.now().minusSeconds(1)
        requireUpdated(
            updated = connectionRepository.updateSchedulingAvailableAt(connectionId, availableAt),
            message = "Connection not found: $connectionId"
        )
        return ResponseEntity.ok(
            DevTimeoutMutationResponse(
                target = "connection-scheduling-availability",
                id = connectionId,
                expiresAt = availableAt
            )
        )
    }

    @PostMapping("/connections/{connectionId}/second-chat-available-now")
    @Transactional
    fun makeSecondChatAvailableNow(
        @PathVariable connectionId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val startsAt = OffsetDateTime.now().minusSeconds(1)
        requireUpdated(
            updated = scheduleNegotiationRepository.updateConfirmedDateTimeByConnectionId(
                connectionId = connectionId,
                confirmedDateTime = startsAt
            ),
            message = "ScheduleNegotiation not found for connection: $connectionId"
        )
        return ResponseEntity.ok(
            DevTimeoutMutationResponse(
                target = "schedule-negotiation",
                id = connectionId,
                expiresAt = startsAt
            )
        )
    }

    @PostMapping("/penalties/{penaltyId}/expire-now")
    @Transactional
    fun expirePenaltyNow(
        @PathVariable penaltyId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val expiresAt = OffsetDateTime.now().minusSeconds(1)
        requireUpdated(
            updated = penaltyRepository.updateExpiresAt(penaltyId, expiresAt),
            message = "Penalty not found: $penaltyId"
        )
        return ResponseEntity.ok(
            DevTimeoutMutationResponse(
                target = "penalty",
                id = penaltyId,
                expiresAt = expiresAt
            )
        )
    }

    private fun requireUpdated(
        updated: Int,
        message: String
    ) {
        if (updated == 0) {
            throw NoSuchElementException(message)
        }
    }
}

data class DevTimeoutMutationResponse(
    val target: String,
    val id: UUID,
    val expiresAt: OffsetDateTime
)
