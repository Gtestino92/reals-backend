package com.reals.backend.repository

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface ChatRepository : JpaRepository<Chat, UUID> {

    fun findByMatchIdAndChatType(
        matchId: UUID,
        chatType: ChatType
    ): Chat?

    fun findByConnectionIdAndChatType(
        connectionId: UUID,
        chatType: ChatType
    ): Chat?

    @Query(
        "select c from Chat c where c.status = 'ACTIVE' and c.timeoutAt <= :now"
    )
    fun findExpiredActiveChats(
        @Param("now") now: OffsetDateTime
    ): List<Chat>

    @Query(
        """ select c from Chat c
        where c.status = 'ACTIVE'
          and (
              c.lastMessageAt is null
              or c.lastMessageAt <= :threshold
          )
        """
    )
    fun findInactiveActiveChats(
        @Param("threshold") threshold: OffsetDateTime
    ): List<Chat>
}