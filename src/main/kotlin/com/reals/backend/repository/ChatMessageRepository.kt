package com.reals.backend.repository

import com.reals.backend.domain.ChatMessage
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime
import java.util.UUID

interface ChatMessageRepository : JpaRepository<ChatMessage, UUID> {

    fun findByChatSessionIdOrderBySentAtAsc(
        chatSessionId: UUID
    ): List<ChatMessage>

    fun findByChatSessionIdAndSentAtAfter(
        chatSessionId: UUID,
        sentAt: OffsetDateTime
    ): List<ChatMessage>

    fun findByChatSessionIdAndSentAtAfterOrderBySentAtAsc(
        chatSessionId: UUID,
        sentAt: OffsetDateTime
    ): List<ChatMessage>

    fun countByChatSessionIdAndSenderId(
        chatSessionId: UUID,
        senderId: UUID
    ): Long

    fun countByChatSessionIdAndSenderIdAndSentAtLessThanEqual(
        chatSessionId: UUID,
        senderId: UUID,
        sentAt: OffsetDateTime
    ): Long
}
