package com.reals.backend.repository

import com.reals.backend.domain.ConversationPromptSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConversationPromptSnapshotRepository : JpaRepository<ConversationPromptSnapshot, UUID> {
    fun findByChatIdOrderByOrdinal(chatId: UUID): List<ConversationPromptSnapshot>

    fun findByChatIdAndOrdinal(
        chatId: UUID,
        ordinal: Int
    ): ConversationPromptSnapshot?

    fun findByChatIdAndId(
        chatId: UUID,
        id: UUID
    ): ConversationPromptSnapshot?

    fun countByChatId(chatId: UUID): Long
}
