package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.*
import com.reals.backend.domain.ChatExitOutcome
import com.reals.backend.service.ChatExitService
import com.reals.backend.service.ChatAudioPolicyService
import com.reals.backend.service.ChatAudioSendResult
import com.reals.backend.service.ChatAudioService
import com.reals.backend.service.ChatAudioUploadGuard
import com.reals.backend.service.ChatService
import com.reals.backend.service.LegalComplianceService
import com.reals.backend.service.S3StorageService
import com.reals.backend.service.exception.DomainConflictException
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.*

@RestController
@RequestMapping("/api/chats")
@Validated
class ChatController(
    private val chatService: ChatService,
    private val chatAudioService: ChatAudioService,
    private val chatAudioUploadGuard: ChatAudioUploadGuard,
    private val chatAudioPolicyService: ChatAudioPolicyService,
    private val storageService: S3StorageService,
    private val chatExitService: ChatExitService,
    private val legalComplianceService: LegalComplianceService
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
            ChatResponse.from(
                c = chat,
                inactivityExpiresAt = chatService.inactivityExpiresAt(chat),
                audioPolicy = ChatAudioPolicyResponse.from(
                    chatAudioPolicyService.policyFor(chat = chat, userId = userId)
                )
            )
        )
    }

    @PostMapping("/{chatId}/messages")
    fun sendMessage(
        @CurrentUserId userId: UUID,
        @PathVariable chatId: UUID,
        @Valid
        @RequestBody request: SendMessageRequest
    ): ResponseEntity<ChatMessageResponse> {
        return when (
            val result = chatService.sendMessageWithResult(
                chatId = chatId,
                senderId = userId,
                content = request.content
            )
        ) {
            is ChatService.SendMessageResult.Sent ->
                ResponseEntity.ok(ChatMessageResponse.from(result.message, ::audioReadUrl))

            is ChatService.SendMessageResult.RejectedAfterResolution ->
                throw DomainConflictException(code = result.code, message = result.message)
        }
    }

    @PostMapping(
        "/{chatId}/audio-messages",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE]
    )
    fun sendAudioMessage(
        @CurrentUserId userId: UUID,
        @PathVariable chatId: UUID,
        @RequestPart("file") file: MultipartFile,
        @RequestPart("clientMessageId") clientMessageId: String
    ): ResponseEntity<ChatMessageResponse> {
        val parsedClientMessageId = UUID.fromString(clientMessageId)
        val result = chatAudioUploadGuard.withPermit {
            chatAudioService.sendAudioMessage(
                chatId = chatId,
                senderId = userId,
                clientMessageId = parsedClientMessageId,
                contentType = file.contentType,
                bytes = file.inputStream.use { it.readBytes() }
            )
        }

        return when (result) {
            is ChatAudioSendResult.Created ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .body(ChatMessageResponse.from(result.message, ::audioReadUrl))
            is ChatAudioSendResult.Replayed ->
                ResponseEntity.ok(ChatMessageResponse.from(result.message, ::audioReadUrl))
        }
    }

    @PutMapping("/{chatId}/messages/{messageId}/reaction")
    fun putMessageReaction(
        @CurrentUserId userId: UUID,
        @PathVariable chatId: UUID,
        @PathVariable messageId: UUID,
        @Valid
        @RequestBody request: PutMessageReactionRequest
    ): ResponseEntity<ChatMessageResponse> =
        ResponseEntity.ok(
            ChatMessageResponse.from(
                chatService.putMessageReaction(
                    chatId = chatId,
                    messageId = messageId,
                    userId = userId,
                    reactionType = request.type
                ),
                ::audioReadUrl
            )
        )

    @PostMapping("/{chatId}/guidance/next-request")
    fun requestNextGuidanceQuestion(
        @CurrentUserId userId: UUID,
        @PathVariable chatId: UUID
    ): ResponseEntity<FirstChatGuidanceResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        return ResponseEntity.ok(
            FirstChatGuidanceResponse.from(
                chatService.requestFirstChatGuidanceNext(
                    chatId = chatId,
                    userId = userId
                )
            )
        )
    }

    @GetMapping("/{chatId}/messages")
    fun getMessages(
        @CurrentUserId userId: UUID,
        @PathVariable chatId: UUID,
        @RequestParam(required = false) after: UUID?,
        @RequestParam(required = false) afterMessageId: UUID?,
        @RequestParam(required = false)
        @Min(1)
        @Max(500)
        limit: Int?
    ): ResponseEntity<Any> {
        val effectiveAfterMessageId = after ?: afterMessageId

        if (effectiveAfterMessageId != null) {
            val page = chatService.getMessagesAfter(
                chatId = chatId,
                userId = userId,
                afterMessageId = effectiveAfterMessageId,
                limit = limit
            )

            return ResponseEntity.ok<Any>(
                ChatMessagesResponse.from(
                    messages = page.messages,
                    hasMore = page.hasMore,
                    audioUrlResolver = ::audioReadUrl
                )
            )
        }

        val messages = chatService.getMessages(
            chatId = chatId,
            userId = userId,
            limit = limit
        )

        return ResponseEntity.ok<Any>(
            messages.map { ChatMessageResponse.from(it, ::audioReadUrl) }
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
            chatExitOutcomeResponse(
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
            chatExitOutcomeResponse(
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
            chatExitOutcomeResponse(
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
                chatExitOutcomeResponse(
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
                chatExitOutcomeResponse(
                    chatExitService.cancelChatForSafety(
                        chatId = chatId,
                        reporterUserId = userId,
                        reason = request.reason,
                        details = request.details
                    )
                )
            )

    private fun chatExitOutcomeResponse(outcome: ChatExitOutcome): ChatExitOutcomeResponse =
        ChatExitOutcomeResponse.from(
            o = outcome,
            inactivityExpiresAt = chatService.inactivityExpiresAt(outcome.chat)
        )

    private fun audioReadUrl(message: com.reals.backend.domain.ChatMessage): String =
        storageService.getReadUrl(
            bucket = requireNotNull(message.audioBucket),
            key = requireNotNull(message.audioObjectKey)
        )

}
