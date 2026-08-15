package com.reals.backend.service

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.ChatReplyTargetType
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ConversationPromptSnapshotRepository
import org.springframework.stereotype.Service
import java.util.UUID

data class ChatMessageReplyPreview(
    val type: ChatReplyTargetType,
    val targetId: UUID,
    val senderId: UUID?,
    val messageType: ChatMessageType?,
    val previewText: String?
)

@Service
class ChatMessageReplyPreviewResolver(
    private val chatMessageRepository: ChatMessageRepository,
    private val promptSnapshotRepository: ConversationPromptSnapshotRepository
) {
    fun resolveFor(messages: Collection<ChatMessage>): Map<UUID, ChatMessageReplyPreview> {
        if (messages.isEmpty()) {
            return emptyMap()
        }

        val replyToMessageIds = messages.mapNotNull { it.replyToMessageId }.toSet()
        val replyToPromptSnapshotIds = messages.mapNotNull { it.replyToPromptSnapshotId }.toSet()

        val messageTargets =
            if (replyToMessageIds.isEmpty()) {
                emptyMap()
            } else {
                chatMessageRepository.findAllById(replyToMessageIds).associateBy { it.id }
            }

        val promptTargets =
            if (replyToPromptSnapshotIds.isEmpty()) {
                emptyMap()
            } else {
                promptSnapshotRepository.findAllById(replyToPromptSnapshotIds).associateBy { it.id }
            }

        return messages.mapNotNull { message ->
            val replyPreview =
                message.replyToMessageId?.let { targetId ->
                    messageTargets[targetId]?.let { target ->
                        ChatMessageReplyPreview(
                            type = ChatReplyTargetType.MESSAGE,
                            targetId = target.id,
                            senderId = target.senderId,
                            messageType = target.messageType,
                            previewText = target.content
                        )
                    }
                } ?: message.replyToPromptSnapshotId?.let { targetId ->
                    promptTargets[targetId]?.let { target ->
                        ChatMessageReplyPreview(
                            type = ChatReplyTargetType.GUIDANCE_QUESTION,
                            targetId = target.id,
                            senderId = null,
                            messageType = null,
                            previewText = target.promptText
                        )
                    }
                }

            replyPreview?.let { message.id to it }
        }.toMap()
    }
}
