package com.reals.backend.repository

import com.reals.backend.domain.ChatExitRequest
import com.reals.backend.domain.ChatExitRequestStatus
import com.reals.backend.domain.ChatExitRequestType
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChatExitRequestRepository : JpaRepository<ChatExitRequest, UUID> {

    fun findByChatIdAndStatusAndType(
        chatId: UUID,
        status: ChatExitRequestStatus,
        type: ChatExitRequestType
    ): ChatExitRequest?

    fun findByChatIdOrderByCreatedAtDesc(chatId: UUID): List<ChatExitRequest>
}
