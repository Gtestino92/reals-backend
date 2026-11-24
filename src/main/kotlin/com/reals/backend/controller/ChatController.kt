package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.*
import com.reals.backend.domain.ChatExitOutcome
import com.reals.backend.domain.ChatReplyTargetType
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.ChatMessage
import com.reals.backend.service.UserBlockService
import com.reals.backend.service.ChatExitService
import com.reals.backend.service.ChatAudioPolicyService
import com.reals.backend.service.ChatAudioSendResult
import com.reals.backend.service.ChatAudioService
import com.reals.backend.service.ChatAudioUploadGuard
import com.reals.backend.service.ChatMessageService
import com.reals.backend.service.ChatAccessService
import com.reals.backend.service.ChatMessageReplyPreviewResolver
import com.reals.backend.service.ChatService
import com.reals.backend.service.LegalComplianceService
import com.reals.backend.service.S3StorageService
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
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
    private val chatMessageService: ChatMessageService,
    private val chatAccessService: ChatAccessService,
    private val userBlockService: UserBlockService,
    private val chatAudioService: ChatAudioService,
    private val chatAudioUploadGuard: ChatAudioUploadGuard,
    private val chatAudioPolicyService: ChatAudioPolicyService,
    private val chatMessageReplyPreviewResolver: ChatMessageReplyPreviewResolver,
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
            val result = chatMessageService.sendMessageWithResult(
                chatId = chatId,
                senderId = userId,
                content = request.content,
                clientMessageId = request.clientMessageId,
                replyTarget = request.replyTo?.let {
                    ChatMessageService.ChatReplyTarget(
                        type = it.type,
                        targetId = it.targetId
                    )
                }
            )
        ) {
            is ChatMessageService.SendMessageResult.Sent ->
                ResponseEntity.ok(messageResponse(result.message, userId))

            is ChatMessageService.SendMessageResult.RejectedAfterResolution ->
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
        @RequestPart("clientMessageId") clientMessageId: String,
        @RequestPart(value = "replyToType", required = false) replyToType: String?,
        @RequestPart(value = "replyToTargetId", required = false) replyToTargetId: String?,
    ): ResponseEntity<ChatMessageResponse> {
        val parsedClientMessageId = UUID.fromString(clientMessageId)
        val parsedReplyToType = replyToType
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching {
                    ChatReplyTargetType.valueOf(raw.uppercase())
                }.getOrElse {
                    throw DomainBadRequestException(
                        code = DomainErrorCode.CHAT_MESSAGE_INVALID,
                        message = "Invalid replyToType",
                    )
                }
            }

        val parsedReplyToTargetId = replyToTargetId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching {
                    UUID.fromString(raw)
                }.getOrElse {
                    throw DomainBadRequestException(
                        code = DomainErrorCode.CHAT_MESSAGE_INVALID,
                        message = "Invalid replyToTargetId",
                    )
                }
            }

        val replyTarget = when {
            parsedReplyToType == null && parsedReplyToTargetId == null -> null

            parsedReplyToType != null && parsedReplyToTargetId != null ->
                ChatMessageService.ChatReplyTarget(
                    type = parsedReplyToType,
                    targetId = parsedReplyToTargetId,
                )

            else -> throw DomainBadRequestException(
                code = DomainErrorCode.CHAT_MESSAGE_INVALID,
                message = "replyToType and replyToTargetId must be provided together",
            )
        }
        val result = chatAudioUploadGuard.withPermit {
            chatAudioService.sendAudioMessage(
                chatId = chatId,
                senderId = userId,
                clientMessageId = parsedClientMessageId,
                contentType = file.contentType,
                bytes = file.inputStream.use { it.readBytes() },
                replyTarget = replyTarget,
            )
        }

        return when (result) {
            is ChatAudioSendResult.Created ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .body(messageResponse(result.message, userId))
            is ChatAudioSendResult.Replayed ->
                ResponseEntity.ok(messageResponse(result.message, userId))
        }
    }

    @PutMapping("/{chatId}/messages/{messageId}/reaction")
    fun putMessageReaction(
        @CurrentUserId userId: UUID,
        @PathVariable chatId: UUID,
        @PathVariable messageId: UUID,
        @Valid
        @RequestBody request: PutMessageReactionRequest
    ): ResponseEntity<ChatMessageResponse> {
        val message =
            chatMessageService.putMessageReaction(
                chatId = chatId,
                messageId = messageId,
                userId = userId,
                reactionType = request.type
            )
        return ResponseEntity.ok(messageResponse(message, userId))
    }

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
            val page = chatMessageService.getMessagesAfter(
                chatId = chatId,
                userId = userId,
                afterMessageId = effectiveAfterMessageId,
                limit = limit
            )

            val body = ChatMessagesResponse.from(
                    messages = page.messages,
                    hasMore = page.hasMore,
                    audioUrlResolver = participantAudioResolver(page.messages, userId),
                    replyPreviews = chatMessageReplyPreviewResolver.resolveFor(page.messages)
                )
            return participantHistoryResponse(chatId, userId, page.messages, body)
        }

        val messages = chatMessageService.getMessages(
            chatId = chatId,
            userId = userId,
            limit = limit
        )

        return participantHistoryResponse(chatId, userId, messages, messageResponses(messages, userId))
    }

    private fun participantHistoryResponse(
        chatId: UUID,
        viewerId: UUID,
        messages: List<ChatMessage>,
        body: Any
    ): ResponseEntity<Any> {
        // Recheck after URL issuance, including empty pages. Blocks are irreversible, so a
        // later restriction must never turn a selected counterpart audio row into a 200.
        val restricted = chatAccessService.isChatPairBlocked(chatId)
        if (restricted && messages.any { it.messageType == ChatMessageType.AUDIO && it.senderId != viewerId }) {
            throw DomainConflictException(
                code = DomainErrorCode.USER_PAIR_BLOCKED,
                message = "Interaction is not available for this user pair"
            )
        }
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .header("X-Reals-Counterpart-Audio-Access", if (restricted) "RESTRICTED" else "ALLOWED")
            .body(body)
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
                        details = request.details,
                        blockUser = request.blockUser
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

    private fun participantAudioResolver(messages: List<ChatMessage>, viewerId: UUID): (ChatMessage) -> String {
        // All messages in a participant response belong to the same two-person chat.
        // If a block committed after page selection, reject the stale response before signing.
        messages.firstOrNull { it.messageType == ChatMessageType.AUDIO && it.senderId != viewerId }?.let {
            userBlockService.requirePairNotBlocked(viewerId, it.senderId)
        }
        return ::audioReadUrl
    }

    private fun messageResponse(message: ChatMessage, viewerId: UUID): ChatMessageResponse =
        ChatMessageResponse.from(
            m = message,
            audioUrlResolver = participantAudioResolver(listOf(message), viewerId),
            replyTo = chatMessageReplyPreviewResolver.resolveFor(listOf(message))[message.id]
        )

    private fun messageResponses(messages: List<ChatMessage>, viewerId: UUID): List<ChatMessageResponse> {
        val replyPreviews = chatMessageReplyPreviewResolver.resolveFor(messages)
        val audioResolver = participantAudioResolver(messages, viewerId)
        return messages.map { message ->
            ChatMessageResponse.from(
                m = message,
                audioUrlResolver = audioResolver,
                replyTo = replyPreviews[message.id]
            )
        }
    }

}
