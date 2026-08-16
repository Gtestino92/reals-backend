package com.reals.backend.integration.service

import com.reals.backend.controller.dto.ChatMessageResponse
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.ChatReplyTargetType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.ChatMessageReplyPreviewResolver
import com.reals.backend.service.ChatService
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class ChatMessageReplyIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var replyPreviewResolver: ChatMessageReplyPreviewResolver

    @Test
    fun `text can reply to partner text and audio messages in same chat`() {
        val setup = createMatchWithFirstChat("reply-targets")
        val partnerText = sendMessageOrThrow(setup.firstChatId, setup.userBId, "mensaje original")
        val partnerAudio = saveAudioMessage(setup.firstChatId, setup.userBId)

        val textReply = sendReply(setup.firstChatId, setup.userAId, "respuesta texto", partnerText.id)
        val audioReply = sendReply(setup.firstChatId, setup.userAId, "respuesta audio", partnerAudio.id)

        assertEquals(partnerText.id, textReply.replyToMessageId)
        assertEquals(partnerAudio.id, audioReply.replyToMessageId)
        assertNull(textReply.replyToPromptSnapshotId)
        assertNull(audioReply.replyToPromptSnapshotId)
    }

    @Test
    fun `message reply target rejects own other-chat and missing targets opaquely`() {
        val setup = createMatchWithFirstChat("reply-invalid")
        val other = createMatchWithFirstChat("reply-invalid-other")
        val ownMessage = sendMessageOrThrow(setup.firstChatId, setup.userAId, "own")
        val otherChatMessage = sendMessageOrThrow(other.firstChatId, other.userBId, "other")

        assertReplyTargetRejected(setup.firstChatId, setup.userAId, ownMessage.id)
        assertReplyTargetRejected(setup.firstChatId, setup.userAId, otherChatMessage.id)
        assertReplyTargetRejected(setup.firstChatId, setup.userAId, UUID.randomUUID())
    }

    @Test
    fun `guidance question reply accepts current and historical same-chat snapshots only in first chat`() {
        val setup = createMatchWithFirstChat("reply-guidance")
        val snapshots = conversationPromptSnapshotRepository.findByChatIdOrderByOrdinal(setup.firstChatId)
        val current = snapshots[0]

        val currentReply =
            sendGuidanceReply(setup.firstChatId, setup.userAId, "actual", current.id)
        assertEquals(current.id, currentReply.replyToPromptSnapshotId)

        chatService.sendMessage(setup.firstChatId, setup.userAId, "a".repeat(60))
        chatService.sendMessage(setup.firstChatId, setup.userBId, "b".repeat(60))
        chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userAId)
        chatService.requestFirstChatGuidanceNext(setup.firstChatId, setup.userBId)

        val historicalReply =
            sendGuidanceReply(setup.firstChatId, setup.userAId, "historica", current.id)
        assertEquals(current.id, historicalReply.replyToPromptSnapshotId)

        val secondChat = createActiveSecondChat()
        assertGuidanceReplyRejected(secondChat.secondChatId, secondChat.userAId, current.id)
        assertGuidanceReplyRejected(setup.firstChatId, setup.userAId, UUID.randomUUID())
    }

    @Test
    fun `response mapping returns shallow message and guidance previews`() {
        val setup = createMatchWithFirstChat("reply-response")
        val first = sendMessageOrThrow(setup.firstChatId, setup.userBId, "primer mensaje")
        val second = sendMessageOrThrow(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            content = "respuesta",
            clientMessageId = UUID.randomUUID(),
            replyTarget = ChatService.ChatReplyTarget(ChatReplyTargetType.MESSAGE, first.id)
        )
        val third = sendMessageOrThrow(
            chatId = setup.firstChatId,
            senderId = setup.userBId,
            content = "respuesta a respuesta",
            clientMessageId = UUID.randomUUID(),
            replyTarget = ChatService.ChatReplyTarget(ChatReplyTargetType.MESSAGE, second.id)
        )
        val snapshot = conversationPromptSnapshotRepository.findByChatIdOrderByOrdinal(setup.firstChatId).first()
        val guidanceReply = sendGuidanceReply(setup.firstChatId, setup.userAId, "sobre pregunta", snapshot.id)

        val previews = replyPreviewResolver.resolveFor(listOf(first, second, third, guidanceReply))
        val ordinary = ChatMessageResponse.from(first, replyTo = previews[first.id])
        val secondResponse = ChatMessageResponse.from(second, replyTo = previews[second.id])
        val thirdResponse = ChatMessageResponse.from(third, replyTo = previews[third.id])
        val guidanceResponse = ChatMessageResponse.from(guidanceReply, replyTo = previews[guidanceReply.id])

        assertNull(ordinary.replyTo)
        assertEquals(first.id, secondResponse.replyTo?.targetId)
        assertEquals("primer mensaje", secondResponse.replyTo?.previewText)
        assertEquals(second.id, thirdResponse.replyTo?.targetId)
        assertEquals("respuesta", thirdResponse.replyTo?.previewText)
        assertNotEquals(first.id, thirdResponse.replyTo?.targetId)
        assertEquals(ChatReplyTargetType.GUIDANCE_QUESTION, guidanceResponse.replyTo?.type)
        assertEquals(snapshot.promptText, guidanceResponse.replyTo?.previewText)
    }

    @Test
    fun `text client message id replay returns canonical message without side effects`() {
        val setup = createMatchWithFirstChat("reply-idempotent")
        val target = sendMessageOrThrow(setup.firstChatId, setup.userBId, "target")
        val clientMessageId = UUID.randomUUID()

        val created = sendMessageOrThrow(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            content = " replay ",
            clientMessageId = clientMessageId,
            replyTarget = ChatService.ChatReplyTarget(ChatReplyTargetType.MESSAGE, target.id)
        )
        val lastMessageAt = chatRepository.findById(setup.firstChatId).orElseThrow().lastMessageAt
        val replayed = sendMessageOrThrow(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            content = "replay",
            clientMessageId = clientMessageId,
            replyTarget = ChatService.ChatReplyTarget(ChatReplyTargetType.MESSAGE, target.id)
        )

        assertEquals(created.id, replayed.id)
        assertEquals(1, chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(setup.firstChatId).count { it.clientMessageId == clientMessageId })
        assertEquals(lastMessageAt, chatRepository.findById(setup.firstChatId).orElseThrow().lastMessageAt)
    }

    @Test
    fun `text client message id conflicts on different content target or message type`() {
        val setup = createMatchWithFirstChat("reply-idempotent-conflict")
        val firstTarget = sendMessageOrThrow(setup.firstChatId, setup.userBId, "target one")
        val secondTarget = sendMessageOrThrow(setup.firstChatId, setup.userBId, "target two")
        val contentKey = UUID.randomUUID()
        val targetKey = UUID.randomUUID()
        val audioKey = UUID.randomUUID()

        sendMessageOrThrow(setup.firstChatId, setup.userAId, "same", clientMessageId = contentKey)
        assertIdempotencyConflict {
            sendMessageOrThrow(setup.firstChatId, setup.userAId, "different", clientMessageId = contentKey)
        }

        sendMessageOrThrow(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            content = "same",
            clientMessageId = targetKey,
            replyTarget = ChatService.ChatReplyTarget(ChatReplyTargetType.MESSAGE, firstTarget.id)
        )
        assertIdempotencyConflict {
            sendMessageOrThrow(
                chatId = setup.firstChatId,
                senderId = setup.userAId,
                content = "same",
                clientMessageId = targetKey,
                replyTarget = ChatService.ChatReplyTarget(ChatReplyTargetType.MESSAGE, secondTarget.id)
            )
        }

        saveAudioMessage(setup.firstChatId, setup.userAId, clientMessageId = audioKey)
        assertIdempotencyConflict {
            sendMessageOrThrow(setup.firstChatId, setup.userAId, "audio key reuse", clientMessageId = audioKey)
        }
    }

    @Test
    fun `text replay succeeds after chat becomes terminal`() {
        val setup = createMatchWithFirstChat("reply-terminal-replay")
        val clientMessageId = UUID.randomUUID()
        val created = sendMessageOrThrow(setup.firstChatId, setup.userAId, "persisted", clientMessageId = clientMessageId)

        chatService.endChat(
            chatId = setup.firstChatId,
            finalStatus = ChatStatus.EXPIRED,
            endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
        )

        val replayed = sendMessageOrThrow(setup.firstChatId, setup.userAId, "persisted", clientMessageId = clientMessageId)
        assertEquals(created.id, replayed.id)
    }

    @Test
    fun `reply target requires client message id but legacy no-reply text remains non idempotent`() {
        val setup = createMatchWithFirstChat("reply-legacy")
        val target = sendMessageOrThrow(setup.firstChatId, setup.userBId, "target")

        val exception = assertThrows<DomainBadRequestException> {
            chatService.sendMessage(
                chatId = setup.firstChatId,
                senderId = setup.userAId,
                content = "no key reply",
                replyTarget = ChatService.ChatReplyTarget(ChatReplyTargetType.MESSAGE, target.id)
            )
        }
        assertEquals(DomainErrorCode.CHAT_MESSAGE_INVALID, exception.code)

        val first = sendMessageOrThrow(setup.firstChatId, setup.userAId, "legacy")
        val second = sendMessageOrThrow(setup.firstChatId, setup.userAId, "legacy")
        assertNotEquals(first.id, second.id)
        assertNull(first.clientMessageId)
        assertNull(second.clientMessageId)
    }

    @Test
    fun `audio replay requires same direct reply target`() {
        val setup = createMatchWithFirstChat("audio-reply-idempotency")
        val firstTarget =
            sendMessageOrThrow(setup.firstChatId, setup.userBId, "target one")
        val secondTarget =
            sendMessageOrThrow(setup.firstChatId, setup.userBId, "target two")
        val clientMessageId = UUID.randomUUID()

        val existing = saveAudioMessage(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            clientMessageId = clientMessageId,
            replyToMessageId = firstTarget.id,
        )

        val replayed = chatService.findAudioMessageReplayOrThrowOnConflict(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            clientMessageId = clientMessageId,
            audioSha256 = AUDIO_SHA256,
            replyTarget = ChatService.ChatReplyTarget(
                ChatReplyTargetType.MESSAGE,
                firstTarget.id,
            ),
        )

        assertEquals(existing.id, replayed?.id)

        assertIdempotencyConflict {
            chatService.findAudioMessageReplayOrThrowOnConflict(
                chatId = setup.firstChatId,
                senderId = setup.userAId,
                clientMessageId = clientMessageId,
                audioSha256 = AUDIO_SHA256,
                replyTarget = ChatService.ChatReplyTarget(
                    ChatReplyTargetType.MESSAGE,
                    secondTarget.id,
                ),
            )
        }
    }

    @Test
    fun `audio replay under chat lock rejects different reply target`() {
        val setup = createMatchWithFirstChat("audio-reply-lock-replay")
        val firstTarget =
            sendMessageOrThrow(setup.firstChatId, setup.userBId, "target one")
        val secondTarget =
            sendMessageOrThrow(setup.firstChatId, setup.userBId, "target two")
        val clientMessageId = UUID.randomUUID()

        val existing = saveAudioMessage(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            clientMessageId = clientMessageId,
            replyToMessageId = firstTarget.id,
        )

        val replayed = chatService.sendAudioMessageWithResult(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            clientMessageId = clientMessageId,
            audioContentType = "audio/mp4",
            audioSizeBytes = 3,
            audioDurationMillis = 1_000,
            audioSha256 = AUDIO_SHA256,
            audioBucket = "unused-on-replay",
            audioObjectKey = "unused-on-replay",
            cleanupTaskId = UUID.randomUUID(),
            messageId = UUID.randomUUID(),
            replyTarget = ChatService.ChatReplyTarget(
                ChatReplyTargetType.MESSAGE,
                firstTarget.id,
            ),
        )

        assertEquals(
            existing.id,
            (replayed as ChatService.SendAudioMessageResult.Replayed).message.id,
        )

        assertIdempotencyConflict {
            chatService.sendAudioMessageWithResult(
                chatId = setup.firstChatId,
                senderId = setup.userAId,
                clientMessageId = clientMessageId,
                audioContentType = "audio/mp4",
                audioSizeBytes = 3,
                audioDurationMillis = 1_000,
                audioSha256 = AUDIO_SHA256,
                audioBucket = "unused-on-conflict",
                audioObjectKey = "unused-on-conflict",
                cleanupTaskId = UUID.randomUUID(),
                messageId = UUID.randomUUID(),
                replyTarget = ChatService.ChatReplyTarget(
                    ChatReplyTargetType.MESSAGE,
                    secondTarget.id,
                ),
            )
        }
    }

    @Test
    fun `audio reply preflight rejects unavailable target before upload`() {
        val setup = createMatchWithFirstChat("audio-reply-preflight")
        val ownMessage =
            sendMessageOrThrow(setup.firstChatId, setup.userAId, "own target")

        val exception = assertThrows<DomainConflictException> {
            chatService.preflightNewAudioMessage(
                chatId = setup.firstChatId,
                senderId = setup.userAId,
                replyTarget = ChatService.ChatReplyTarget(
                    ChatReplyTargetType.MESSAGE,
                    ownMessage.id,
                ),
            )
        }

        assertEquals(
            DomainErrorCode.CHAT_MESSAGE_REPLY_TARGET_NOT_AVAILABLE,
            exception.code,
        )
    }

    private fun sendReply(
        chatId: UUID,
        senderId: UUID,
        content: String,
        targetMessageId: UUID
    ): ChatMessage =
        sendMessageOrThrow(
            chatId = chatId,
            senderId = senderId,
            content = content,
            clientMessageId = UUID.randomUUID(),
            replyTarget = ChatService.ChatReplyTarget(ChatReplyTargetType.MESSAGE, targetMessageId)
        )

    private fun sendGuidanceReply(
        chatId: UUID,
        senderId: UUID,
        content: String,
        snapshotId: UUID
    ): ChatMessage =
        sendMessageOrThrow(
            chatId = chatId,
            senderId = senderId,
            content = content,
            clientMessageId = UUID.randomUUID(),
            replyTarget = ChatService.ChatReplyTarget(ChatReplyTargetType.GUIDANCE_QUESTION, snapshotId)
        )

    private fun assertReplyTargetRejected(
        chatId: UUID,
        senderId: UUID,
        targetMessageId: UUID
    ) {
        val exception = assertThrows<DomainConflictException> {
            sendReply(chatId, senderId, "rechazo", targetMessageId)
        }
        assertEquals(DomainErrorCode.CHAT_MESSAGE_REPLY_TARGET_NOT_AVAILABLE, exception.code)
    }

    private fun assertGuidanceReplyRejected(
        chatId: UUID,
        senderId: UUID,
        snapshotId: UUID
    ) {
        val exception = assertThrows<DomainConflictException> {
            sendGuidanceReply(chatId, senderId, "rechazo", snapshotId)
        }
        assertEquals(DomainErrorCode.CHAT_MESSAGE_REPLY_TARGET_NOT_AVAILABLE, exception.code)
    }

    private fun assertIdempotencyConflict(block: () -> Unit) {
        val exception = assertThrows<DomainConflictException> {
            block()
        }
        assertEquals(DomainErrorCode.CHAT_MESSAGE_IDEMPOTENCY_CONFLICT, exception.code)
    }

    private fun saveAudioMessage(
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID = UUID.randomUUID(),
        replyToMessageId: UUID? = null,
        replyToPromptSnapshotId: UUID? = null,
    ): ChatMessage =
        chatMessageRepository.saveAndFlush(
            ChatMessage(
                chatSessionId = chatId,
                senderId = senderId,
                messageType = ChatMessageType.AUDIO,
                clientMessageId = clientMessageId,
                content = null,
                audioBucket = "reals-media-test",
                audioObjectKey = "chats/$chatId/messages/${UUID.randomUUID()}.m4a",
                audioContentType = "audio/mp4",
                audioSizeBytes = 3,
                audioDurationMillis = 1_000,
                audioSha256 = AUDIO_SHA256,
                replyToMessageId = replyToMessageId,
                replyToPromptSnapshotId = replyToPromptSnapshotId,
            )
        )

    private companion object {
        const val AUDIO_SHA256 =
            "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"
    }
}
