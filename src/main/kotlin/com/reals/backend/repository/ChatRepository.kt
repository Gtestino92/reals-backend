package com.reals.backend.repository

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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

    fun findByMatchIdInAndChatType(
        matchIds: Collection<UUID>,
        chatType: ChatType
    ): List<Chat>

    fun findByConnectionIdInAndChatType(
        connectionIds: Collection<UUID>,
        chatType: ChatType
    ): List<Chat>

    fun findByMatchIdInAndStatusIn(
        matchIds: Collection<UUID>,
        statuses: Collection<ChatStatus>
    ): List<Chat>

    fun findByConnectionIdInAndStatusIn(
        connectionIds: Collection<UUID>,
        statuses: Collection<ChatStatus>
    ): List<Chat>

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
              (c.lastMessageAt is null and c.startedAt <= :threshold)
              or c.lastMessageAt <= :threshold
          )
        """
    )
    fun findInactiveActiveChats(
        @Param("threshold") threshold: OffsetDateTime
    ): List<Chat>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Chat c set c.timeoutAt = :timeoutAt where c.id = :chatId")
    fun updateTimeoutAt(
        @Param("chatId") chatId: UUID,
        @Param("timeoutAt") timeoutAt: OffsetDateTime
    ): Int
}
