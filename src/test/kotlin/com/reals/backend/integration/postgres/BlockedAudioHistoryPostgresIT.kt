package com.reals.backend.integration.postgres

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.UserBlockSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class BlockedAudioHistoryPostgresIT : PostgresITBase() {
    @Test
    fun `blocked pages order timestamp ties and accept a hidden cursor`() {
        val pair = createMatchWithFirstChat()
        val time = OffsetDateTime.parse("2026-07-01T12:00:00Z")
        val chat = pair.firstChatId
        val viewer = pair.userBId
        fun audio(id: Long, sender: UUID) = ChatMessage(
            id = UUID(0, id), chatSessionId = chat, senderId = sender,
            messageType = ChatMessageType.AUDIO, clientMessageId = UUID.randomUUID(),
            audioBucket = "fixture", audioObjectKey = "voice-$id", audioContentType = "audio/mp4",
            audioDurationMillis = 1000, audioSizeBytes = 32, audioSha256 = "a".repeat(64), sentAt = time
        )
        val cursor = audio(1, pair.userAId)
        val own = audio(2, viewer)
        val text = ChatMessage(
            id = UUID(0, 4), chatSessionId = chat, senderId = pair.userAId, content = "history", sentAt = time
        )
        chatMessageRepository.saveAllAndFlush(listOf(text, audio(5, pair.userAId), own, audio(3, pair.userAId), cursor))
        userBlockCommandService.blockUserAndContain(viewer, pair.userAId, UserBlockSource.MANUAL)

        assertEquals(listOf(own.id, text.id), chatService.getMessages(chat, viewer, limit = 2).map { it.id })
        val first = chatService.getMessagesAfter(chat, viewer, cursor.id, limit = 1)
        assertEquals(listOf(own.id), first.messages.map { it.id })
        assertTrue(first.hasMore)
        val last = chatService.getMessagesAfter(chat, viewer, own.id, limit = 1)
        assertEquals(listOf(text.id), last.messages.map { it.id })
        assertFalse(last.hasMore)
    }
}
