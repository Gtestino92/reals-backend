package com.reals.backend.integration.service

import com.reals.backend.controller.dto.ChatMessageResponse
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageReactionType
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException
import java.time.OffsetDateTime
import java.util.UUID

class ChatMessageReactionIntegrationTest : BaseIT() {

    @Test
    fun `partner messages before own reply remain reactable`() {
        val setup = createMatchWithFirstChat("reaction-example-a")
        val messages = conversation(
            setup.firstChatId,
            setup.userAId,
            setup.userBId,
            listOf(Other("A"), Other("B"), Me("C"), Me("D"))
        )
        val previousLastMessageAt = chatRepository.findById(setup.firstChatId).orElseThrow().lastMessageAt

        assertHeart(setup.firstChatId, messages["A"]!!, setup.userAId)
        assertHeart(setup.firstChatId, messages["B"]!!, setup.userAId)

        assertEquals(previousLastMessageAt, chatRepository.findById(setup.firstChatId).orElseThrow().lastMessageAt)
    }

    @Test
    fun `new incoming block supersedes previous unreacted block`() {
        val setup = createMatchWithFirstChat("reaction-example-b")
        val messages = conversation(
            setup.firstChatId,
            setup.userAId,
            setup.userBId,
            listOf(Other("A"), Other("B"), Me("C"), Me("D"), Other("E"), Other("F"))
        )

        assertReactionRejected(setup.firstChatId, messages["A"]!!, setup.userAId)
        assertReactionRejected(setup.firstChatId, messages["B"]!!, setup.userAId)
        assertHeart(setup.firstChatId, messages["E"]!!, setup.userAId)
        assertHeart(setup.firstChatId, messages["F"]!!, setup.userAId)
    }

    @Test
    fun `own messages after latest incoming block do not invalidate it`() {
        val setup = createMatchWithFirstChat("reaction-example-c")
        val messages = conversation(
            setup.firstChatId,
            setup.userAId,
            setup.userBId,
            listOf(Other("A"), Other("B"), Me("C"), Me("D"), Other("E"), Other("F"), Me("G"), Me("H"))
        )

        assertHeart(setup.firstChatId, messages["E"]!!, setup.userAId)
        assertHeart(setup.firstChatId, messages["F"]!!, setup.userAId)
    }

    @Test
    fun `initial incoming block is reactable when there is no previous own message`() {
        val setup = createMatchWithFirstChat("reaction-example-d")
        val messages = conversation(
            setup.firstChatId,
            setup.userAId,
            setup.userBId,
            listOf(Other("A"), Other("B"))
        )

        assertHeart(setup.firstChatId, messages["A"]!!, setup.userAId)
        assertHeart(setup.firstChatId, messages["B"]!!, setup.userAId)
    }

    @Test
    fun `sender cannot react to own message`() {
        val setup = createMatchWithFirstChat("reaction-own")
        val message = sendMessageOrThrow(setup.firstChatId, setup.userAId, "own message")

        assertReactionRejected(setup.firstChatId, message, setup.userAId)
    }

    @Test
    fun `message from another chat is rejected after participant validation`() {
        val setup = createMatchWithFirstChat("reaction-chat")
        val other = createMatchWithFirstChat("reaction-other-chat")
        val otherMessage = sendMessageOrThrow(other.firstChatId, other.userBId, "other chat message")

        val exception = assertThrows<DomainConflictException> {
            chatService.putMessageReaction(
                chatId = setup.firstChatId,
                messageId = otherMessage.id,
                userId = setup.userAId,
                reactionType = ChatMessageReactionType.HEART
            )
        }

        assertEquals(DomainErrorCode.CHAT_NOT_AVAILABLE, exception.code)
    }

    @Test
    fun `non participant cannot react`() {
        val setup = createMatchWithFirstChat("reaction-stranger")
        val message = sendMessageOrThrow(setup.firstChatId, setup.userBId, "partner message")

        assertThrows<AccessDeniedException> {
            chatService.putMessageReaction(
                chatId = setup.firstChatId,
                messageId = message.id,
                userId = UUID.randomUUID(),
                reactionType = ChatMessageReactionType.HEART
            )
        }
    }

    @Test
    fun `text and audio messages are reactable`() {
        val setup = createMatchWithFirstChat("reaction-types")
        val text = sendMessageOrThrow(setup.firstChatId, setup.userBId, "text message")
        val audio = saveAudioMessage(setup.firstChatId, setup.userBId, "audio")

        assertHeart(setup.firstChatId, text, setup.userAId)
        assertHeart(setup.firstChatId, audio, setup.userAId)
    }

    @Test
    fun `second chat message is reactable while ordinary interaction is writable`() {
        val setup = createActiveSecondChat()
        val message = sendMessageOrThrow(setup.secondChatId, setup.userBId, "second chat partner message")

        assertHeart(setup.secondChatId, message, setup.userAId)
    }

    @Test
    fun `terminal chat rejects new heart but keeps existing heart readable and idempotent`() {
        val setup = createMatchWithFirstChat("reaction-terminal")
        val reacted = sendMessageOrThrow(setup.firstChatId, setup.userBId, "already reacted")
        val unreacted = sendMessageOrThrow(setup.firstChatId, setup.userBId, "unreacted")
        assertHeart(setup.firstChatId, reacted, setup.userAId)

        chatService.endChat(
            chatId = setup.firstChatId,
            finalStatus = ChatStatus.EXPIRED,
            endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
        )

        assertHeart(setup.firstChatId, reacted, setup.userAId)
        val messages = chatService.getMessages(setup.firstChatId, setup.userAId)
        assertEquals(ChatMessageReactionType.HEART, messages.single { it.id == reacted.id }.reactionType)

        val exception = assertThrows<DomainConflictException> {
            chatService.putMessageReaction(
                chatId = setup.firstChatId,
                messageId = unreacted.id,
                userId = setup.userAId,
                reactionType = ChatMessageReactionType.HEART
            )
        }
        assertEquals(DomainErrorCode.CHAT_EXPIRED, exception.code)
    }

    @Test
    fun `duplicate put succeeds without changing state`() {
        val setup = createMatchWithFirstChat("reaction-duplicate")
        val message = sendMessageOrThrow(setup.firstChatId, setup.userBId, "partner message")

        assertHeart(setup.firstChatId, message, setup.userAId)
        assertHeart(setup.firstChatId, message, setup.userAId)

        assertEquals(
            ChatMessageReactionType.HEART,
            chatMessageRepository.findById(message.id).orElseThrow().reactionType
        )
    }

    @Test
    fun `idempotent retry succeeds after block expires`() {
        val setup = createMatchWithFirstChat("reaction-retry")
        val first = sendMessageOrThrow(setup.firstChatId, setup.userBId, "first incoming")
        assertHeart(setup.firstChatId, first, setup.userAId)
        sendMessageOrThrow(setup.firstChatId, setup.userAId, "boundary")
        sendMessageOrThrow(setup.firstChatId, setup.userBId, "new incoming")

        assertHeart(setup.firstChatId, first, setup.userAId)
    }

    @Test
    fun `aged out unreacted message is rejected`() {
        val setup = createMatchWithFirstChat("reaction-aged-out")
        val old = sendMessageOrThrow(setup.firstChatId, setup.userBId, "old incoming")
        sendMessageOrThrow(setup.firstChatId, setup.userAId, "boundary")
        sendMessageOrThrow(setup.firstChatId, setup.userBId, "new incoming")

        assertReactionRejected(setup.firstChatId, old, setup.userAId)
    }

    @Test
    fun `message dto maps null and heart reaction`() {
        val setup = createMatchWithFirstChat("reaction-dto")
        val message = sendMessageOrThrow(setup.firstChatId, setup.userBId, "dto message")

        assertNull(ChatMessageResponse.from(message).reactionType)
        assertHeart(setup.firstChatId, message, setup.userAId)

        val reloaded = chatMessageRepository.findById(message.id).orElseThrow()
        assertEquals(ChatMessageReactionType.HEART, ChatMessageResponse.from(reloaded).reactionType)
    }

    @Test
    fun `reactable block uses sent at and id ordering for equal timestamps`() {
        val setup = createMatchWithFirstChat("reaction-order")
        val sentAt = OffsetDateTime.now()
        val oldIncoming = saveTextMessage(
            chatId = setup.firstChatId,
            senderId = setup.userBId,
            content = "old incoming",
            sentAt = sentAt,
            id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        )
        saveTextMessage(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            content = "boundary",
            sentAt = sentAt,
            id = UUID.fromString("00000000-0000-0000-0000-000000000002")
        )
        val latestIncoming = saveTextMessage(
            chatId = setup.firstChatId,
            senderId = setup.userBId,
            content = "latest incoming",
            sentAt = sentAt,
            id = UUID.fromString("00000000-0000-0000-0000-000000000003")
        )

        assertReactionRejected(setup.firstChatId, oldIncoming, setup.userAId)
        assertHeart(setup.firstChatId, latestIncoming, setup.userAId)
    }

    private fun assertHeart(
        chatId: UUID,
        message: ChatMessage,
        userId: UUID
    ) {
        val reacted =
            chatService.putMessageReaction(
                chatId = chatId,
                messageId = message.id,
                userId = userId,
                reactionType = ChatMessageReactionType.HEART
            )
        assertEquals(ChatMessageReactionType.HEART, reacted.reactionType)
        assertEquals(ChatMessageReactionType.HEART, chatMessageRepository.findById(message.id).orElseThrow().reactionType)
    }

    private fun assertReactionRejected(
        chatId: UUID,
        message: ChatMessage,
        userId: UUID
    ) {
        val exception = assertThrows<DomainConflictException> {
            chatService.putMessageReaction(
                chatId = chatId,
                messageId = message.id,
                userId = userId,
                reactionType = ChatMessageReactionType.HEART
            )
        }
        assertEquals(DomainErrorCode.CHAT_MESSAGE_REACTION_NOT_AVAILABLE, exception.code)
        assertNotEquals(ChatMessageReactionType.HEART, chatMessageRepository.findById(message.id).orElseThrow().reactionType)
    }

    private fun conversation(
        chatId: UUID,
        me: UUID,
        other: UUID,
        entries: List<ConversationEntry>
    ): Map<String, ChatMessage> {
        val base = OffsetDateTime.now().minusSeconds(entries.size.toLong())
        return entries.mapIndexed { index, entry ->
            val senderId = if (entry.fromMe) me else other
            entry.label to sendMessageOrThrow(
                chatId = chatId,
                senderId = senderId,
                content = entry.label,
                now = base.plusSeconds(index.toLong())
            )
        }.toMap()
    }

    private fun saveTextMessage(
        chatId: UUID,
        senderId: UUID,
        content: String,
        sentAt: OffsetDateTime,
        id: UUID = UUID.randomUUID()
    ): ChatMessage =
        chatMessageRepository.saveAndFlush(
            ChatMessage(
                id = id,
                chatSessionId = chatId,
                senderId = senderId,
                messageType = ChatMessageType.TEXT,
                content = content,
                sentAt = sentAt
            )
        )

    private fun saveAudioMessage(
        chatId: UUID,
        senderId: UUID,
        label: String
    ): ChatMessage =
        chatMessageRepository.saveAndFlush(
            ChatMessage(
                chatSessionId = chatId,
                senderId = senderId,
                messageType = ChatMessageType.AUDIO,
                clientMessageId = UUID.randomUUID(),
                content = null,
                audioBucket = "reals-media-test",
                audioObjectKey = "chats/$chatId/messages/${UUID.randomUUID()}.m4a",
                audioContentType = "audio/mp4",
                audioSizeBytes = 3,
                audioDurationMillis = 1_000,
                audioSha256 = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                sentAt = OffsetDateTime.now().plusNanos(label.hashCode().toLong() and 1023L)
            )
        )

    private sealed class ConversationEntry(
        val label: String,
        val fromMe: Boolean
    )

    private class Me(label: String) : ConversationEntry(label, true)

    private class Other(label: String) : ConversationEntry(label, false)
}
