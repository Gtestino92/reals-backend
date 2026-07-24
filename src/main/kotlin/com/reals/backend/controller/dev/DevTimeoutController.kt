package com.reals.backend.controller.dev

import com.reals.backend.domain.Chat
import com.reals.backend.domain.SecondChatResolutionRequestStatus
import com.reals.backend.domain.SecondChatResolutionRequestType
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.SecondChatResolutionRequestRepository
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
    private val chatMessageRepository: ChatMessageRepository,
    private val connectionRepository: ConnectionRepository,
    private val penaltyRepository: PenaltyRepository,
    private val scheduleNegotiationRepository: ScheduleNegotiationRepository,
    private val secondChatResolutionRequestRepository: SecondChatResolutionRequestRepository,
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

    @PostMapping("/connections/{connectionId}/second-chat-late-window-now")
    @Transactional
    fun makeSecondChatLateWindowNow(
        @PathVariable connectionId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> =
        moveConfirmedSecondChatTime(
            connectionId = connectionId,
            startsAt = OffsetDateTime.now().minusMinutes(10),
            target = "schedule-negotiation-second-chat-late-window"
        )

    @PostMapping("/connections/{connectionId}/second-chat-before-hard-cutoff")
    @Transactional
    fun makeSecondChatBeforeHardCutoff(
        @PathVariable connectionId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> =
        moveConfirmedSecondChatTime(
            connectionId = connectionId,
            startsAt = OffsetDateTime.now().minusMinutes(19).minusSeconds(59),
            target = "schedule-negotiation-second-chat-before-hard-cutoff"
        )

    @PostMapping("/connections/{connectionId}/second-chat-past-hard-cutoff")
    @Transactional
    fun makeSecondChatPastHardCutoff(
        @PathVariable connectionId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> =
        moveConfirmedSecondChatTime(
            connectionId = connectionId,
            startsAt = OffsetDateTime.now().minusMinutes(20).minusSeconds(1),
            target = "schedule-negotiation-second-chat-past-hard-cutoff"
        )

    @PostMapping("/chats/{chatId}/second-chat-conversation-started-past")
    @Transactional
    fun moveSecondChatConversationStartedPast(
        @PathVariable chatId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val startedAt = OffsetDateTime.now().minusMinutes(11)
        val chat = chatRepository.findByIdForUpdate(chatId)
            ?: throw NoSuchElementException("Chat not found: $chatId")
        chat.conversationStartedAt = startedAt
        chatRepository.save(chat)
        return ResponseEntity.ok(
            DevTimeoutMutationResponse(
                target = "second-chat-conversation-started",
                id = chatId,
                expiresAt = startedAt
            )
        )
    }

    @PostMapping("/chats/{chatId}/latest-message-before-inactivity-claim")
    @Transactional
    fun moveLatestMessageBeforeInactivityClaim(
        @PathVariable chatId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> =
        moveLatestSecondChatMessageTime(
            chatId = chatId,
            sentAt = OffsetDateTime.now().minusMinutes(4).minusSeconds(59),
            target = "second-chat-latest-message-before-inactivity-claim"
        )

    @PostMapping("/chats/{chatId}/latest-message-before-conversation-started")
    @Transactional
    fun moveLatestMessageBeforeConversationStarted(
        @PathVariable chatId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val chat = chatRepository.findByIdForUpdate(chatId)
            ?: throw NoSuchElementException("Chat not found: $chatId")
        val conversationStartedAt = chat.conversationStartedAt
            ?: throw IllegalArgumentException("Second chat conversation has not started: $chatId")
        return moveLatestSecondChatMessageTime(
            chat = chat,
            sentAt = conversationStartedAt.minusMinutes(1),
            target = "second-chat-latest-message-before-conversation-started"
        )
    }

    @PostMapping("/chats/{chatId}/latest-message-claimable")
    @Transactional
    fun moveLatestMessageClaimable(
        @PathVariable chatId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> =
        moveLatestSecondChatMessageTime(
            chatId = chatId,
            sentAt = OffsetDateTime.now().minusMinutes(5),
            target = "second-chat-latest-message-claimable"
        )

    @PostMapping("/chats/{chatId}/latest-message-before-automatic-inactivity")
    @Transactional
    fun moveLatestMessageBeforeAutomaticInactivity(
        @PathVariable chatId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> =
        moveLatestSecondChatMessageTime(
            chatId = chatId,
            sentAt = OffsetDateTime.now().minusMinutes(9).minusSeconds(59),
            target = "second-chat-latest-message-before-automatic-inactivity"
        )

    @PostMapping("/chats/{chatId}/latest-message-automatic-inactivity-due")
    @Transactional
    fun moveLatestMessageAutomaticInactivityDue(
        @PathVariable chatId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> =
        moveLatestSecondChatMessageTime(
            chatId = chatId,
            sentAt = OffsetDateTime.now().minusMinutes(10).minusSeconds(1),
            target = "second-chat-latest-message-automatic-inactivity-due"
        )

    @PostMapping("/second-chat-resolution-requests/{requestId}/expire-now")
    @Transactional
    fun expireSecondChatResolutionRequestNow(
        @PathVariable requestId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val expiresAt = OffsetDateTime.now().minusSeconds(1)
        val request = secondChatResolutionRequestRepository.findByIdForUpdate(requestId)
            ?: throw NoSuchElementException("Second-chat resolution request not found: $requestId")
        request.expiresAt = expiresAt
        secondChatResolutionRequestRepository.save(request)
        return ResponseEntity.ok(
            DevTimeoutMutationResponse(
                target = "second-chat-resolution-request-expiry",
                id = requestId,
                expiresAt = expiresAt
            )
        )
    }

    @PostMapping("/second-chat-resolution-requests/{requestId}/completion-cooldown-active")
    @Transactional
    fun makeCompletionCooldownActive(
        @PathVariable requestId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> =
        moveCompletionRequestResolvedAt(
            requestId = requestId,
            resolvedAt = OffsetDateTime.now().minusSeconds(30),
            target = "second-chat-completion-cooldown-active"
        )

    @PostMapping("/second-chat-resolution-requests/{requestId}/completion-cooldown-expired")
    @Transactional
    fun makeCompletionCooldownExpired(
        @PathVariable requestId: UUID
    ): ResponseEntity<DevTimeoutMutationResponse> =
        moveCompletionRequestResolvedAt(
            requestId = requestId,
            resolvedAt = OffsetDateTime.now().minusSeconds(61),
            target = "second-chat-completion-cooldown-expired"
        )

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

    private fun moveConfirmedSecondChatTime(
        connectionId: UUID,
        startsAt: OffsetDateTime,
        target: String
    ): ResponseEntity<DevTimeoutMutationResponse> {
        requireUpdated(
            updated = scheduleNegotiationRepository.updateConfirmedDateTimeByConnectionId(
                connectionId = connectionId,
                confirmedDateTime = startsAt
            ),
            message = "ScheduleNegotiation not found for connection: $connectionId"
        )
        return ResponseEntity.ok(
            DevTimeoutMutationResponse(
                target = target,
                id = connectionId,
                expiresAt = startsAt
            )
        )
    }

    private fun moveLatestSecondChatMessageTime(
        chatId: UUID,
        sentAt: OffsetDateTime,
        target: String
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val chat = chatRepository.findByIdForUpdate(chatId)
            ?: throw NoSuchElementException("Chat not found: $chatId")
        return moveLatestSecondChatMessageTime(
            chat = chat,
            sentAt = sentAt,
            target = target
        )
    }

    private fun moveLatestSecondChatMessageTime(
        chat: Chat,
        sentAt: OffsetDateTime,
        target: String
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val latestMessage = chatMessageRepository.findTopByChatSessionIdOrderBySentAtDescIdDesc(chat.id)
            ?: throw NoSuchElementException("No messages found for chat: ${chat.id}")
        latestMessage.sentAt = sentAt
        chatMessageRepository.save(latestMessage)
        chat.lastMessageAt = sentAt
        chat.lastMessageSenderId = latestMessage.senderId
        chatRepository.save(chat)
        return ResponseEntity.ok(
            DevTimeoutMutationResponse(
                target = target,
                id = chat.id,
                expiresAt = sentAt
            )
        )
    }

    private fun moveCompletionRequestResolvedAt(
        requestId: UUID,
        resolvedAt: OffsetDateTime,
        target: String
    ): ResponseEntity<DevTimeoutMutationResponse> {
        val request = secondChatResolutionRequestRepository.findByIdForUpdate(requestId)
            ?: throw NoSuchElementException("Second-chat resolution request not found: $requestId")
        if (request.type != SecondChatResolutionRequestType.MUTUAL_COMPLETION) {
            throw IllegalArgumentException("Second-chat resolution request is not mutual completion: $requestId")
        }
        if (request.status == SecondChatResolutionRequestStatus.PENDING) {
            request.status = SecondChatResolutionRequestStatus.TIMED_OUT
        }
        request.resolvedAt = resolvedAt
        secondChatResolutionRequestRepository.save(request)
        return ResponseEntity.ok(
            DevTimeoutMutationResponse(
                target = target,
                id = requestId,
                expiresAt = resolvedAt
            )
        )
    }
}

data class DevTimeoutMutationResponse(
    val target: String,
    val id: UUID,
    val expiresAt: OffsetDateTime
)
