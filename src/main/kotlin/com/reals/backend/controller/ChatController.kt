package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.*
import com.reals.backend.service.ChatExitService
import com.reals.backend.service.ChatService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/chats")
class ChatController(
    private val chatService: ChatService,
    private val chatExitService: ChatExitService
) {

    @GetMapping("/{chatId}")
    fun getChat(
        @CurrentUserId userId: UUID,
        @PathVariable chatId: UUID
    ): ResponseEntity<ChatResponse> {

        val chat = chatService.findByIdForUserOrThrow(
            chatId = chatId,
            userId = userId
        )

        return ResponseEntity.ok(
            ChatResponse.from(chat)
        )
    }

    @PostMapping("/{chatId}/messages")
    fun sendMessage(
        @CurrentUserId userId: UUID,
        @PathVariable chatId: UUID,
        @Valid
        @RequestBody request: SendMessageRequest
    ): ResponseEntity<ChatMessageResponse> {

        val message = chatService.sendMessage(
            chatId = chatId,
            senderId = userId,
            content = request.content
        )

        return ResponseEntity.ok(
            ChatMessageResponse.from(message)
        )
    }

    @GetMapping("/{chatId}/messages")
    fun getMessages(
        @CurrentUserId userId: UUID,
        @PathVariable chatId: UUID,
        @RequestParam(required = false) after: UUID?,
        @RequestParam(required = false) afterMessageId: UUID?
    ): ResponseEntity<Any> {
        val effectiveAfterMessageId = after ?: afterMessageId

        if (effectiveAfterMessageId != null) {
            val messages = chatService.getMessagesAfter(
                chatId = chatId,
                userId = userId,
                afterMessageId = effectiveAfterMessageId
            )

            return ResponseEntity.ok<Any>(
                ChatMessagesResponse.from(messages)
            )
        }

        val messages = chatService.getMessages(
            chatId = chatId,
            userId = userId
        )

        return ResponseEntity.ok<Any>(
            messages.map { ChatMessageResponse.from(it) }
        )
    }

    @PostMapping("/{chatId}/exit-requests")
    fun requestMutualCancellation(
        @PathVariable chatId: UUID,
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: ChatExitRequestCreateRequest
    ): ResponseEntity<ChatExitRequestResponse> {
        val result =
            chatExitService.requestMutualCancellationWithResult(
                chatId = chatId,
                requesterUserId = userId,
                reason = request.reason,
                details = request.details
            )

        val body = ChatExitRequestResponse.from(result.exitRequest)

        return if (result.created) {
            ResponseEntity.status(HttpStatus.CREATED).body(body)
        } else {
            ResponseEntity.ok(body)
        }
    }

    @GetMapping("/{chatId}/exit-requests")
    fun getExitRequests(
        @PathVariable chatId: UUID,
        @CurrentUserId userId: UUID
    ): ResponseEntity<List<ChatExitRequestResponse>> =
        ResponseEntity.ok(
            chatExitService.findExitRequests(chatId, userId)
                .map { ChatExitRequestResponse.from(it) }
        )

    @PostMapping("/{chatId}/exit-requests/{exitRequestId}/acceptance")
    fun acceptMutualCancellation(
        @PathVariable chatId: UUID,
        @PathVariable exitRequestId: UUID,
        @CurrentUserId userId: UUID
    ): ResponseEntity<ChatExitOutcomeResponse> =
        ResponseEntity.ok(
            ChatExitOutcomeResponse.from(
                chatExitService.acceptMutualCancellation(
                    chatId = chatId,
                    requestId = exitRequestId,
                    responderUserId = userId
                )
            )
        )

    @PostMapping("/{chatId}/exit-requests/{exitRequestId}/rejection")
    fun rejectMutualCancellation(
        @PathVariable chatId: UUID,
        @PathVariable exitRequestId: UUID,
        @CurrentUserId userId: UUID
    ): ResponseEntity<ChatExitOutcomeResponse> =
        ResponseEntity.ok(
            ChatExitOutcomeResponse.from(
                chatExitService.rejectMutualCancellation(
                    chatId = chatId,
                    requestId = exitRequestId,
                    responderUserId = userId
                )
            )
        )

    @PostMapping("/{chatId}/exit-requests/{exitRequestId}/timeout")
    fun timeoutMutualCancellation(
        @PathVariable chatId: UUID,
        @PathVariable exitRequestId: UUID,
        @CurrentUserId userId: UUID
    ): ResponseEntity<ChatExitOutcomeResponse> =
        ResponseEntity.ok(
            ChatExitOutcomeResponse.from(
                chatExitService.timeoutMutualCancellation(
                    chatId = chatId,
                    requestId = exitRequestId,
                    userId = userId
                )
            )
        )

    @PostMapping("/{chatId}/cancellations")
    fun cancelChat(
        @PathVariable chatId: UUID,
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: ChatCancellationRequest
    ): ResponseEntity<ChatExitOutcomeResponse> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(
                ChatExitOutcomeResponse.from(
                    chatExitService.cancelChatUnilaterally(
                        chatId = chatId,
                        userId = userId,
                        reason = request.reason,
                        details = request.details
                    )
                )
            )

    @PostMapping("/{chatId}/safety-cancellations")
    fun cancelChatForSafety(
        @PathVariable chatId: UUID,
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: ChatSafetyCancellationRequest
    ): ResponseEntity<ChatExitOutcomeResponse> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(
                ChatExitOutcomeResponse.from(
                    chatExitService.cancelChatForSafety(
                        chatId = chatId,
                        reporterUserId = userId,
                        reason = request.reason,
                        details = request.details
                    )
                )
            )

}
