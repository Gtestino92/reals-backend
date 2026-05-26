package com.reals.backend.repository

import com.reals.backend.domain.ChatDecision
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChatDecisionRepository : JpaRepository<ChatDecision, UUID> {
    fun findByChatId(chatId: UUID): ChatDecision?
    fun findByMatchId(matchId: UUID): ChatDecision?
}