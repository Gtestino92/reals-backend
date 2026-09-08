package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageReactionType
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.ChatReplyTargetType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.SecondChatAttendanceStatus
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConversationPromptSnapshotRepository
import com.reals.backend.repository.SecondChatParticipationRepository
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class ChatMessageService(
    private val chatRepository: ChatRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val promptSnapshotRepository: ConversationPromptSnapshotRepository,
    private val secondChatParticipationRepository: SecondChatParticipationRepository,
    private val chatAccessService: ChatAccessService,
    private val chatLifecycleService: ChatLifecycleService,
    private val firstChatDecisionPolicyService: FirstChatDecisionPolicyService,
    private val secondChatConversationLifecycleService: SecondChatConversationLifecycleService,
    private val chatAudioPolicyService: ChatAudioPolicyService,
    private val mediaCleanupTaskService: MediaCleanupTaskService,
    private val readMetrics: ReadMetrics,

    @param:Value("\${chat.messages.page-limit-default:200}")
    private val defaultMessagePageLimit: Int
) {

    sealed interface SendMessageResult {
        data class Sent(val message: ChatMessage) : SendMessageResult
        data class RejectedAfterResolution(
            val code: DomainErrorCode,
            val message: String
        ) : SendMessageResult
    }

    data class ChatReplyTarget(
        val type: ChatReplyTargetType,
        val targetId: UUID
    )

    private data class ResolvedReplyTarget(
        val replyToMessageId: UUID?,
        val replyToPromptSnapshotId: UUID?
    )

    sealed interface SendAudioMessageResult {
        data class Created(val message: ChatMessage) : SendAudioMessageResult
        data class Replayed(val message: ChatMessage) : SendAudioMessageResult
        data class RejectedAfterResolution(
            val code: DomainErrorCode,
            val message: String
        ) : SendAudioMessageResult
    }

    data class ChatMessagesPage(
        val messages: List<ChatMessage>,
        val hasMore: Boolean
    )

    fun sendMessage(
        chatId: UUID,
        senderId: UUID,
        content: String,
        clientMessageId: UUID? = null,
        replyTarget: ChatReplyTarget? = null
    ): ChatMessage =
        when (val result = sendMessageWithResult(chatId, senderId, content, clientMessageId, replyTarget)) {
            is SendMessageResult.Sent -> result.message
            is SendMessageResult.RejectedAfterResolution ->
                throw DomainConflictException(code = result.code, message = result.message)
        }

    fun sendMessageWithResult(
        chatId: UUID,
        senderId: UUID,
        content: String,
        clientMessageId: UUID? = null,
        replyTarget: ChatReplyTarget? = null,
        now: OffsetDateTime? = null
    ): SendMessageResult {
        val normalizedContent = normalizeMessageContent(content)

        val chat = chatAccessService.findByIdForUpdateOrThrow(chatId)
        chatAccessService.validateChatParticipant(chat, senderId)

        if (replyTarget != null && clientMessageId == null) {
            throw DomainBadRequestException(
                code = DomainErrorCode.CHAT_MESSAGE_INVALID,
                message = "clientMessageId is required when replyTo is provided"
            )
        }

        if (clientMessageId != null) {
            chatMessageRepository.findByChatSessionIdAndSenderIdAndClientMessageId(
                chatSessionId = chatId,
                senderId = senderId,
                clientMessageId = clientMessageId
            )?.let { existing ->
                validateTextReplayOrThrow(
                    existing = existing,
                    normalizedContent = normalizedContent,
                    replyTarget = replyTarget
                )
                return SendMessageResult.Sent(existing)
            }
        }

        val effectiveNow = now ?: OffsetDateTime.now()

        chatAccessService.requireChatPairNotBlocked(chat)
        chatLifecycleService.validateActiveChatWindow(chat)
        requireSecondChatJoinedForMessage(chat, senderId)
        if (chat.chatType == ChatType.FIRST_CHAT) {
            firstChatDecisionPolicyService.requireOrdinaryFirstChatMutationAllowed(chat, senderId)
            chatLifecycleService.requireNoPendingMutualCancellation(chat.id)
        }

        when (
            val lifecycleResult =
                secondChatConversationLifecycleService.beforeSecondChatMessage(
                    chat = chat,
                    senderId = senderId,
                    now = effectiveNow
                )
        ) {
            is SecondChatConversationLifecycleService.SecondChatMessageResult.Continue -> Unit
            is SecondChatConversationLifecycleService.SecondChatMessageResult.RejectedAfterResolution ->
                return SendMessageResult.RejectedAfterResolution(
                    code = lifecycleResult.code,
                    message = lifecycleResult.message
                )
        }

        val resolvedReplyTarget = resolveReplyTarget(
            chat = chat,
            senderId = senderId,
            replyTarget = replyTarget
        )

        val message =
            chatMessageRepository.save(
                ChatMessage(
                    chatSessionId = chat.id,
                    senderId = senderId,
                    messageType = ChatMessageType.TEXT,
                    clientMessageId = clientMessageId,
                    content = normalizedContent,
                    replyToMessageId = resolvedReplyTarget.replyToMessageId,
                    replyToPromptSnapshotId = resolvedReplyTarget.replyToPromptSnapshotId,
                    sentAt = effectiveNow
                )
            )

        chat.lastMessageAt = maxOf(chat.lastMessageAt ?: message.sentAt, message.sentAt)
        if (chat.chatType == ChatType.SECOND_CHAT) {
            chat.lastMessageSenderId = senderId
        }
        chatRepository.save(chat)

        return SendMessageResult.Sent(message)
    }

    fun findAudioMessageReplayOrThrowOnConflict(
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID,
        audioSha256: String,
        replyTarget: ChatReplyTarget? = null,
    ): ChatMessage? {
        val chat = chatAccessService.findByIdOrThrow(chatId)
        chatAccessService.validateChatParticipant(chat, senderId)
        val existing =
            chatMessageRepository.findByChatSessionIdAndSenderIdAndClientMessageId(
                chatSessionId = chatId,
                senderId = senderId,
                clientMessageId = clientMessageId
            ) ?: return null
        validateAudioReplayOrThrow(existing, audioSha256, replyTarget)
        return existing
    }

    fun preflightNewAudioMessage(
        chatId: UUID,
        senderId: UUID,
        now: OffsetDateTime = OffsetDateTime.now(),
        replyTarget: ChatReplyTarget? = null,
    ) {
        val chat = chatAccessService.findByIdOrThrow(chatId)
        chatAccessService.validateChatParticipant(chat, senderId)
        chatAccessService.requireChatPairNotBlocked(chat)
        chatLifecycleService.validateActiveChatWindowSideEffectFree(chat, now)
        requireSecondChatJoinedForMessage(chat, senderId)
        resolveReplyTarget(
            chat = chat,
            senderId = senderId,
            replyTarget = replyTarget,
        )
        if (chat.chatType == ChatType.FIRST_CHAT) {
            firstChatDecisionPolicyService.requireOrdinaryFirstChatMutationAllowed(chat, senderId)
            chatLifecycleService.requireNoPendingMutualCancellation(chat.id)
        }
        chatAudioPolicyService.requireAudioEnabled(
            chat = chat,
            userId = senderId,
            now = now
        )
    }

    fun sendAudioMessageWithResult(
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID,
        audioContentType: String,
        audioSizeBytes: Long,
        audioDurationMillis: Long,
        audioSha256: String,
        audioBucket: String,
        audioObjectKey: String,
        cleanupTaskId: UUID,
        messageId: UUID,
        now: OffsetDateTime = OffsetDateTime.now(),
        replyTarget: ChatReplyTarget? = null,
    ): SendAudioMessageResult {
        val chat = chatAccessService.findByIdForUpdateOrThrow(chatId)
        chatAccessService.validateChatParticipant(chat, senderId)
        chatMessageRepository.findByChatSessionIdAndSenderIdAndClientMessageId(
            chatSessionId = chatId,
            senderId = senderId,
            clientMessageId = clientMessageId
        )?.let { existing ->
            validateAudioReplayOrThrow(existing, audioSha256, replyTarget)
            return SendAudioMessageResult.Replayed(existing)
        }
        val resolvedReplyTarget = resolveReplyTarget(
            chat = chat,
            senderId = senderId,
            replyTarget = replyTarget,
        )
        chatAccessService.requireChatPairNotBlocked(chat)
        chatLifecycleService.validateActiveChatWindow(chat)
        requireSecondChatJoinedForMessage(chat, senderId)
        if (chat.chatType == ChatType.FIRST_CHAT) {
            firstChatDecisionPolicyService.requireOrdinaryFirstChatMutationAllowed(chat, senderId)
            chatLifecycleService.requireNoPendingMutualCancellation(chat.id)
        }

        when (
            val lifecycleResult =
                secondChatConversationLifecycleService.beforeSecondChatMessage(
                    chat = chat,
                    senderId = senderId,
                    now = now
                )
        ) {
            is SecondChatConversationLifecycleService.SecondChatMessageResult.Continue -> Unit
            is SecondChatConversationLifecycleService.SecondChatMessageResult.RejectedAfterResolution ->
                return SendAudioMessageResult.RejectedAfterResolution(
                    code = lifecycleResult.code,
                    message = lifecycleResult.message
                )
        }

        chatAudioPolicyService.requireAudioEnabled(
            chat = chat,
            userId = senderId,
            now = now
        )

        val message =
            chatMessageRepository.saveAndFlush(
                ChatMessage(
                    id = messageId,
                    chatSessionId = chat.id,
                    senderId = senderId,
                    messageType = ChatMessageType.AUDIO,
                    clientMessageId = clientMessageId,
                    content = null,
                    audioBucket = audioBucket,
                    audioObjectKey = audioObjectKey,
                    audioContentType = audioContentType,
                    audioSizeBytes = audioSizeBytes,
                    audioDurationMillis = audioDurationMillis,
                    audioSha256 = audioSha256,
                    sentAt = now,
                    replyToMessageId = resolvedReplyTarget.replyToMessageId,
                    replyToPromptSnapshotId = resolvedReplyTarget.replyToPromptSnapshotId,
                )
            )

        chat.lastMessageAt = maxOf(chat.lastMessageAt ?: message.sentAt, message.sentAt)
        if (chat.chatType == ChatType.SECOND_CHAT) {
            chat.lastMessageSenderId = senderId
        }
        chatRepository.save(chat)
        mediaCleanupTaskService.deleteTaskInCurrentTransaction(cleanupTaskId)

        return SendAudioMessageResult.Created(message)
    }

    fun putMessageReaction(
        chatId: UUID,
        messageId: UUID,
        userId: UUID,
        reactionType: ChatMessageReactionType,
        now: OffsetDateTime = OffsetDateTime.now()
    ): ChatMessage {
        if (reactionType != ChatMessageReactionType.HEART) {
            throw reactionNotAvailable()
        }

        val chat = chatAccessService.findByIdForUpdateOrThrow(chatId)
        chatAccessService.validateChatParticipant(chat, userId)

        val message =
            chatMessageRepository.findById(messageId)
                .orElseThrow { chatLifecycleService.chatNotAvailable() }

        if (message.chatSessionId != chatId) {
            throw chatLifecycleService.chatNotAvailable()
        }

        if (message.senderId == userId) {
            throw reactionNotAvailable()
        }

        if (message.reactionType == ChatMessageReactionType.HEART) {
            return message
        }

        chatAccessService.requireChatPairNotBlocked(chat)
        chatLifecycleService.validateActiveChatWindow(chat)
        requireSecondChatJoinedForMessage(chat, userId)
        if (chat.chatType == ChatType.FIRST_CHAT) {
            firstChatDecisionPolicyService.requireOrdinaryFirstChatMutationAllowed(chat, userId)
            chatLifecycleService.requireNoPendingMutualCancellation(chat.id)
        }
        secondChatConversationLifecycleService.requireSecondChatMetadataMutationAllowed(
            chat = chat,
            now = now
        )

        if (!isCurrentlyReactableMessage(chatId = chatId, userId = userId, message = message)) {
            throw reactionNotAvailable()
        }

        message.reactionType = ChatMessageReactionType.HEART
        return chatMessageRepository.save(message)
    }

    fun getMessages(
        chatId: UUID,
        userId: UUID,
        limit: Int? = null
    ): List<ChatMessage> =
        readMetrics.recordChatMessageRead(ReadMetrics.CHAT_MODE_INITIAL) {
            val messages = getMessagesMeasured(
                chatId = chatId,
                userId = userId,
                limit = limit
            )
            readMetrics.recordReturnedChatMessages(
                mode = ReadMetrics.CHAT_MODE_INITIAL,
                count = messages.size
            )
            messages
        }

    fun getMessagesAfter(
        chatId: UUID,
        userId: UUID,
        afterMessageId: UUID,
        limit: Int? = null
    ): ChatMessagesPage =
        readMetrics.recordChatMessageRead(ReadMetrics.CHAT_MODE_INCREMENTAL) {
            val page = getMessagesAfterMeasured(
                chatId = chatId,
                userId = userId,
                afterMessageId = afterMessageId,
                limit = limit
            )
            readMetrics.recordReturnedChatMessages(
                mode = ReadMetrics.CHAT_MODE_INCREMENTAL,
                count = page.messages.size
            )
            page
        }

    private fun getMessagesMeasured(
        chatId: UUID,
        userId: UUID,
        limit: Int?
    ): List<ChatMessage> {
        val chat = chatAccessService.findByIdOrThrow(chatId)
        chatAccessService.validateChatParticipant(chat, userId)
        chatLifecycleService.validateChatReadable(chat)
        val pageLimit = resolveMessagePageLimit(limit)

        return chatMessageRepository.findByChatSessionIdOrderBySentAtDescIdDesc(
            chatSessionId = chatId,
            pageable = PageRequest.of(0, pageLimit)
        ).asReversed()
    }

    private fun getMessagesAfterMeasured(
        chatId: UUID,
        userId: UUID,
        afterMessageId: UUID,
        limit: Int?
    ): ChatMessagesPage {
        val chat = chatAccessService.findByIdOrThrow(chatId)
        chatAccessService.validateChatParticipant(chat, userId)
        chatLifecycleService.validateChatReadable(chat)
        val pageLimit = resolveMessagePageLimit(limit)

        val afterMessage =
            chatMessageRepository.findById(afterMessageId)
                .orElseThrow {
                    chatLifecycleService.chatNotAvailable()
                }

        if (afterMessage.chatSessionId != chatId) {
            throw chatLifecycleService.chatNotAvailable()
        }

        val page = chatMessageRepository.findPageAfterCursor(
            chatSessionId = chatId,
            cursorId = afterMessage.id,
            messageId = afterMessage.id.toString(),
            pageable = PageRequest.of(0, pageLimit + 1)
        )

        return ChatMessagesPage(
            messages = page.take(pageLimit),
            hasMore = page.size > pageLimit
        )
    }

    private fun validateTextReplayOrThrow(
        existing: ChatMessage,
        normalizedContent: String,
        replyTarget: ChatReplyTarget?
    ) {
        val (requestedReplyToMessageId, requestedReplyToPromptSnapshotId) = getReplyIdsForTarget(replyTarget)

        if (
            existing.messageType != ChatMessageType.TEXT ||
            existing.content != normalizedContent ||
            existing.replyToMessageId != requestedReplyToMessageId ||
            existing.replyToPromptSnapshotId != requestedReplyToPromptSnapshotId
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.CHAT_MESSAGE_IDEMPOTENCY_CONFLICT,
                message = "Client message id was already used with a different text message payload"
            )
        }
    }

    private fun resolveReplyTarget(
        chat: Chat,
        senderId: UUID,
        replyTarget: ChatReplyTarget?
    ): ResolvedReplyTarget {
        if (replyTarget == null) {
            return ResolvedReplyTarget(
                replyToMessageId = null,
                replyToPromptSnapshotId = null
            )
        }

        return when (replyTarget.type) {
            ChatReplyTargetType.MESSAGE -> {
                val target =
                    chatMessageRepository.findById(replyTarget.targetId)
                        .orElseThrow { replyTargetNotAvailable() }

                if (
                    target.chatSessionId != chat.id ||
                    target.senderId == senderId ||
                    target.messageType !in setOf(ChatMessageType.TEXT, ChatMessageType.AUDIO)
                ) {
                    throw replyTargetNotAvailable()
                }

                ResolvedReplyTarget(
                    replyToMessageId = target.id,
                    replyToPromptSnapshotId = null
                )
            }

            ChatReplyTargetType.GUIDANCE_QUESTION -> {
                if (chat.chatType != ChatType.FIRST_CHAT) {
                    throw replyTargetNotAvailable()
                }

                val snapshot =
                    promptSnapshotRepository.findByChatIdAndId(
                        chatId = chat.id,
                        id = replyTarget.targetId
                    ) ?: throw replyTargetNotAvailable()

                ResolvedReplyTarget(
                    replyToMessageId = null,
                    replyToPromptSnapshotId = snapshot.id
                )
            }
        }
    }

    private fun getReplyIdsForTarget(replyTarget: ChatReplyTarget?): Pair<UUID?, UUID?> {
        val requestedReplyToMessageId =
            replyTarget?.takeIf { it.type == ChatReplyTargetType.MESSAGE }?.targetId
        val requestedReplyToPromptSnapshotId =
            replyTarget?.takeIf { it.type == ChatReplyTargetType.GUIDANCE_QUESTION }?.targetId
        return Pair(requestedReplyToMessageId, requestedReplyToPromptSnapshotId)
    }

    private fun requireSecondChatJoinedForMessage(
        chat: Chat,
        senderId: UUID
    ) {
        if (chat.chatType != ChatType.SECOND_CHAT) {
            return
        }
        val connectionId = chat.connectionId ?: throw chatLifecycleService.chatNotAvailable()
        val participation =
            secondChatParticipationRepository.findByConnectionIdAndUserId(
                connectionId = connectionId,
                userId = senderId
            )
        if (
            participation?.attendanceStatus != SecondChatAttendanceStatus.ON_TIME &&
            participation?.attendanceStatus != SecondChatAttendanceStatus.LATE
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.SECOND_CHAT_JOIN_REQUIRED,
                message = "Second chat for connection $connectionId requires explicit join before sending messages"
            )
        }
    }

    private fun normalizeMessageContent(content: String): String {
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        if (normalized.isBlank()) {
            throw invalidChatMessage()
        }

        if (normalized.length > MESSAGE_MAX_LENGTH) {
            throw invalidChatMessage()
        }

        if (
            normalized.any {
                (it.isISOControl() && it != '\n') ||
                    it == '<' ||
                    it == '>'
            }
        ) {
            throw invalidChatMessage()
        }

        return normalized
    }

    private fun invalidChatMessage(): DomainBadRequestException =
        DomainBadRequestException(
            code = DomainErrorCode.CHAT_MESSAGE_INVALID,
            message = "Chat message is invalid"
        )

    private fun reactionNotAvailable(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_MESSAGE_REACTION_NOT_AVAILABLE,
            message = "Chat message reaction is not available"
        )

    private fun replyTargetNotAvailable(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_MESSAGE_REPLY_TARGET_NOT_AVAILABLE,
            message = "Chat message reply target is not available"
        )

    private fun isCurrentlyReactableMessage(
        chatId: UUID,
        userId: UUID,
        message: ChatMessage
    ): Boolean {
        val latestIncoming =
            chatMessageRepository.findLatestIncomingMessage(
                chatSessionId = chatId,
                userId = userId
            ) ?: return false

        if (compareMessageOrder(message, latestIncoming) > 0) {
            return false
        }

        val boundary =
            chatMessageRepository.findLatestOwnMessageBefore(
                chatSessionId = chatId,
                userId = userId,
                cursorId = latestIncoming.id,
                messageId = latestIncoming.id.toString()
            )

        return boundary == null || compareMessageOrder(message, boundary) > 0
    }

    private fun compareMessageOrder(
        left: ChatMessage,
        right: ChatMessage
    ): Int {
        val sentAtComparison = left.sentAt.compareTo(right.sentAt)
        return if (sentAtComparison != 0) {
            sentAtComparison
        } else {
            left.id.toString().compareTo(right.id.toString())
        }
    }

    private fun resolveMessagePageLimit(limit: Int?): Int {
        val resolvedLimit = limit ?: defaultMessagePageLimit

        require(resolvedLimit in 1..MESSAGE_PAGE_LIMIT_MAX) {
            "Message limit must be between 1 and $MESSAGE_PAGE_LIMIT_MAX"
        }

        return resolvedLimit
    }

    private fun validateAudioReplayOrThrow(
        existing: ChatMessage,
        audioSha256: String,
        replyTarget: ChatReplyTarget?,
    ) {
        val (requestedReplyToMessageId, requestedReplyToPromptSnapshotId) =
            getReplyIdsForTarget(replyTarget)

        if (
            existing.messageType != ChatMessageType.AUDIO ||
            existing.audioSha256 != audioSha256 ||
            existing.replyToMessageId != requestedReplyToMessageId ||
            existing.replyToPromptSnapshotId != requestedReplyToPromptSnapshotId
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.CHAT_MESSAGE_IDEMPOTENCY_CONFLICT,
                message = "Client message id was already used with a different audio message payload"
            )
        }
    }

    private companion object {
        const val MESSAGE_MAX_LENGTH = 1000
        const val MESSAGE_PAGE_LIMIT_MAX = 500
    }
}
