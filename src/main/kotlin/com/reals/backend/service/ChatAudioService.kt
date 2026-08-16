package com.reals.backend.service

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

sealed interface ChatAudioSendResult {
    data class Created(val message: ChatMessage) : ChatAudioSendResult
    data class Replayed(val message: ChatMessage) : ChatAudioSendResult
}

@Service
class ChatAudioService(
    private val validationService: ChatAudioValidationService,
    private val storageService: S3StorageService,
    private val mediaCleanupTaskService: MediaCleanupTaskService,
    private val chatService: ChatService
) {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun sendAudioMessage(
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID,
        contentType: String?,
        bytes: ByteArray,
        replyTarget: ChatService.ChatReplyTarget? = null,
    ): ChatAudioSendResult {
        val inspection = validationService.inspect(contentType, bytes)
        val sha256 = sha256Hex(bytes)

        chatService.findAudioMessageReplayOrThrowOnConflict(
            chatId = chatId,
            senderId = senderId,
            clientMessageId = clientMessageId,
            audioSha256 = sha256,
            replyTarget = replyTarget,
        )?.let { return ChatAudioSendResult.Replayed(it) }

        chatService.preflightNewAudioMessage(
            chatId = chatId,
            senderId = senderId,
            replyTarget = replyTarget,
        )

        val messageId = UUID.randomUUID()
        val objectKey = storageService.chatAudioObjectKey(chatId = chatId, messageId = messageId)
        val guardTask = mediaCleanupTaskService.createGuardTask(
            storageProvider = PhotoStorageProvider.S3,
            bucket = storageService.mediaBucket(),
            objectKey = objectKey
        )
        val storedObject =
            storageService.uploadChatAudio(
                chatId = chatId,
                messageId = messageId,
                contentType = inspection.contentType,
                bytes = bytes
            )

        return try {
            when (
                val result = chatService.sendAudioMessageWithResult(
                    chatId = chatId,
                    senderId = senderId,
                    clientMessageId = clientMessageId,
                    audioContentType = inspection.contentType,
                    audioSizeBytes = inspection.sizeBytes,
                    audioDurationMillis = inspection.durationMillis,
                    audioSha256 = sha256,
                    audioBucket = storedObject.bucket,
                    audioObjectKey = storedObject.key,
                    cleanupTaskId = guardTask.id,
                    messageId = messageId,
                    replyTarget = replyTarget,
                )
            ) {
                is ChatService.SendAudioMessageResult.Created ->
                    ChatAudioSendResult.Created(result.message)
                is ChatService.SendAudioMessageResult.Replayed ->
                    ChatAudioSendResult.Replayed(result.message)
                is ChatService.SendAudioMessageResult.RejectedAfterResolution ->
                    throw DomainConflictException(code = result.code, message = result.message)
            }
        } catch (ex: DataIntegrityViolationException) {
            val winner = chatService.findAudioMessageReplayOrThrowOnConflict(
                chatId = chatId,
                senderId = senderId,
                clientMessageId = clientMessageId,
                audioSha256 = sha256,
                replyTarget = replyTarget,
            ) ?: throw DomainConflictException(
                code = DomainErrorCode.CHAT_MESSAGE_IDEMPOTENCY_CONFLICT,
                message = "Audio message idempotency race could not be resolved"
            )
            ChatAudioSendResult.Replayed(winner)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return HexFormat.of().formatHex(digest)
    }
}
