package com.reals.backend.repository

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Chat c where c.connectionId = :connectionId and c.chatType = :chatType")
    fun findByConnectionIdAndChatTypeForUpdate(
        @Param("connectionId") connectionId: UUID,
        @Param("chatType") chatType: ChatType
    ): Chat?

    @Query("select c.status from Chat c where c.id = :chatId")
    fun findStatusById(
        @Param("chatId") chatId: UUID
    ): ChatStatus?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Chat c where c.id = :chatId")
    fun findByIdForUpdate(
        @Param("chatId") chatId: UUID
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update Chat c
        set c.status = 'ACTIVE',
            c.startedAt = :activatedAt,
            c.activatedAt = :activatedAt
        where c.id = :chatId
          and c.chatType = 'SECOND_CHAT'
          and c.status = 'AVAILABLE'
        """
    )
    fun activateAvailableSecondChat(
        @Param("chatId") chatId: UUID,
        @Param("activatedAt") activatedAt: OffsetDateTime
    ): Int

    @Query(
        "select c from Chat c where c.status = 'ACTIVE' and c.chatType = 'FIRST_CHAT' and c.timeoutAt <= :now"
    )
    fun findExpiredActiveFirstChats(
        @Param("now") now: OffsetDateTime
    ): List<Chat>

    @Query(
        """
        select c.id from Chat c
        where c.status = 'ACTIVE'
          and c.chatType = 'FIRST_CHAT'
          and c.timeoutAt <= :now
        order by c.timeoutAt asc, c.id asc
        """
    )
    fun findExpiredActiveFirstChatIds(
        @Param("now") now: OffsetDateTime,
        pageable: Pageable
    ): List<UUID>

    @Query(
        """ select c from Chat c
        where c.status = 'ACTIVE'
          and c.chatType = 'FIRST_CHAT'
          and (
              (c.lastMessageAt is null and c.startedAt <= :threshold)
              or c.lastMessageAt <= :threshold
          )
          and not exists (
              select d.id from ChatDecision d
              where d.chatId = c.id
                and (
                    (d.userADecision = 'APPROVED' and d.userBDecision is null)
                    or (d.userADecision is null and d.userBDecision = 'APPROVED')
                )
          )
        """
    )
    fun findInactiveActiveChats(
        @Param("threshold") threshold: OffsetDateTime
    ): List<Chat>

    @Query(
        """ select c.id from Chat c
        where c.status = 'ACTIVE'
          and c.chatType = 'FIRST_CHAT'
          and (
              (c.lastMessageAt is null and c.startedAt <= :threshold)
              or c.lastMessageAt <= :threshold
          )
          and not exists (
              select d.id from ChatDecision d
              where d.chatId = c.id
                and (
                    (d.userADecision = 'APPROVED' and d.userBDecision is null)
                    or (d.userADecision is null and d.userBDecision = 'APPROVED')
                )
          )
        order by coalesce(c.lastMessageAt, c.startedAt) asc, c.id asc
        """
    )
    fun findInactiveActiveChatIds(
        @Param("threshold") threshold: OffsetDateTime,
        pageable: Pageable
    ): List<UUID>

    @Query(
        "select c from Chat c where c.status = 'ACTIVE' and c.chatType = 'SECOND_CHAT' and c.timeoutAt <= :now"
    )
    fun findTimedOutActiveSecondChats(
        @Param("now") now: OffsetDateTime
    ): List<Chat>

    @Query(
        """select c.id from Chat c
           where c.status = 'ACTIVE'
             and c.chatType = 'SECOND_CHAT'
             and c.timeoutAt <= :now
           order by c.timeoutAt asc, c.id asc"""
    )
    fun findTimedOutActiveSecondChatIds(
        @Param("now") now: OffsetDateTime,
        pageable: Pageable
    ): List<UUID>

    @Query(
        "select c from Chat c where c.status = 'AVAILABLE' and c.chatType = 'SECOND_CHAT' and c.timeoutAt <= :now"
    )
    fun findTimedOutAvailableSecondChats(
        @Param("now") now: OffsetDateTime
    ): List<Chat>

    @Query(
        """select c.id from Chat c
           where c.status = 'AVAILABLE'
             and c.chatType = 'SECOND_CHAT'
             and c.timeoutAt <= :now
           order by c.timeoutAt asc, c.id asc"""
    )
    fun findTimedOutAvailableSecondChatIds(
        @Param("now") now: OffsetDateTime,
        pageable: Pageable
    ): List<UUID>

    @Query(
        """select c from Chat c
           where c.status in ('FINISHED', 'EXPIRED', 'ABANDONED')
             and c.chatType = 'SECOND_CHAT'
             and c.readOnlyUntil is not null
             and c.readOnlyUntil <= :now"""
    )
    fun findExpiredReadOnlySecondChats(
        @Param("now") now: OffsetDateTime
    ): List<Chat>

    @Query(
        """select c.id from Chat c
           where c.status in ('FINISHED', 'EXPIRED', 'ABANDONED')
             and c.chatType = 'SECOND_CHAT'
             and c.readOnlyUntil is not null
             and c.readOnlyUntil <= :now
           order by c.readOnlyUntil asc, c.id asc"""
    )
    fun findExpiredReadOnlySecondChatIds(
        @Param("now") now: OffsetDateTime,
        pageable: Pageable
    ): List<UUID>

    @Query(
        """select c.id from Chat c
           where c.status = 'ACTIVE'
             and c.chatType = 'SECOND_CHAT'
             and c.conversationStartedAt is not null
             and c.lastMessageAt is null
             and c.conversationStartedAt <= :dueBefore
           order by c.conversationStartedAt asc, c.id asc"""
    )
    fun findInitialSilenceDueSecondChatIds(
        @Param("dueBefore") dueBefore: OffsetDateTime,
        pageable: Pageable
    ): List<UUID>

    @Query(
        """select c.id from Chat c
           where c.status = 'ACTIVE'
             and c.chatType = 'SECOND_CHAT'
             and c.conversationStartedAt is not null
             and c.lastMessageAt is not null
             and c.lastMessageSenderId is not null
             and (
                 (c.lastMessageAt >= c.conversationStartedAt and c.lastMessageAt <= :dueBefore)
                 or (c.lastMessageAt < c.conversationStartedAt and c.conversationStartedAt <= :dueBefore)
             )
           order by case when c.lastMessageAt >= c.conversationStartedAt then c.lastMessageAt else c.conversationStartedAt end asc,
                    c.id asc"""
    )
    fun findAutomaticInactivityDueSecondChatIds(
        @Param("dueBefore") dueBefore: OffsetDateTime,
        pageable: Pageable
    ): List<UUID>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Chat c set c.timeoutAt = :timeoutAt where c.id = :chatId")
    fun updateTimeoutAt(
        @Param("chatId") chatId: UUID,
        @Param("timeoutAt") timeoutAt: OffsetDateTime
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Chat c set c.readOnlyUntil = :readOnlyUntil where c.id = :chatId")
    fun updateReadOnlyUntil(
        @Param("chatId") chatId: UUID,
        @Param("readOnlyUntil") readOnlyUntil: OffsetDateTime
    ): Int
}
