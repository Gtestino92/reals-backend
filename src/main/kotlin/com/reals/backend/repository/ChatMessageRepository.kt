package com.reals.backend.repository

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface ChatMessageRepository : JpaRepository<ChatMessage, UUID> {

    fun findByChatSessionIdOrderBySentAtAsc(
        chatSessionId: UUID
    ): List<ChatMessage>

    fun findByChatSessionIdOrderBySentAtDescIdDesc(
        chatSessionId: UUID,
        pageable: Pageable
    ): List<ChatMessage>

    fun findByChatSessionIdAndSentAtAfter(
        chatSessionId: UUID,
        sentAt: OffsetDateTime
    ): List<ChatMessage>

    fun findByChatSessionIdAndSentAtAfterOrderBySentAtAsc(
        chatSessionId: UUID,
        sentAt: OffsetDateTime
    ): List<ChatMessage>

    @Query(
        value =
        """
        select *
        from chat_messages
        where chat_session_id = :chatSessionId
          and (
            sent_at > (
              select cursor.sent_at
              from chat_messages cursor
              where cursor.id = :cursorId
            )
            or (
              sent_at = (
                select cursor.sent_at
                from chat_messages cursor
                where cursor.id = :cursorId
              )
              and cast(id as varchar) > :messageId
            )
          )
        order by sent_at asc, cast(id as varchar) asc
        """,
        nativeQuery = true
    )
    fun findPageAfterCursor(
        @Param("chatSessionId") chatSessionId: UUID,
        @Param("cursorId") cursorId: UUID,
        @Param("messageId") messageId: String,
        pageable: Pageable
    ): List<ChatMessage>

    fun countByChatSessionIdAndSenderId(
        chatSessionId: UUID,
        senderId: UUID
    ): Long

    fun countByChatSessionIdAndSenderIdAndMessageType(
        chatSessionId: UUID,
        senderId: UUID,
        messageType: ChatMessageType
    ): Long

    fun findByChatSessionIdAndSenderIdAndClientMessageId(
        chatSessionId: UUID,
        senderId: UUID,
        clientMessageId: UUID
    ): ChatMessage?

    fun existsByChatSessionIdAndSenderId(
        chatSessionId: UUID,
        senderId: UUID
    ): Boolean

    fun findTopByChatSessionIdOrderBySentAtDescIdDesc(
        chatSessionId: UUID
    ): ChatMessage?

    @Query(
        value = """
            select *
            from chat_messages
            where chat_session_id = :chatSessionId
              and sender_id <> :userId
            order by sent_at desc, cast(id as varchar) desc
            limit 1
        """,
        nativeQuery = true
    )
    fun findLatestIncomingMessage(
        @Param("chatSessionId") chatSessionId: UUID,
        @Param("userId") userId: UUID
    ): ChatMessage?

    @Query(
        value = """
            select *
            from chat_messages
            where chat_session_id = :chatSessionId
              and sender_id = :userId
              and (
                sent_at < (
                  select cursor.sent_at
                  from chat_messages cursor
                  where cursor.id = :cursorId
                )
                or (
                  sent_at = (
                    select cursor.sent_at
                    from chat_messages cursor
                    where cursor.id = :cursorId
                  )
                  and cast(id as varchar) < :messageId
                )
              )
            order by sent_at desc, cast(id as varchar) desc
            limit 1
        """,
        nativeQuery = true
    )
    fun findLatestOwnMessageBefore(
        @Param("chatSessionId") chatSessionId: UUID,
        @Param("userId") userId: UUID,
        @Param("cursorId") cursorId: UUID,
        @Param("messageId") messageId: String
    ): ChatMessage?

    @Query(
        value = """
            select coalesce(
                sum(
                    length(content) *
                    case
                        when reply_to_prompt_snapshot_id = :currentPromptSnapshotId
                            then :directQuestionReplyMultiplier
                        else 1
                    end
                ),
                0
            )
            from chat_messages
            where chat_session_id = :chatId
              and sender_id = :senderId
              and message_type = 'TEXT'
              and content is not null
              and sent_at >= :sentAt
        """,
        nativeQuery = true
    )
    fun sumParticipationScoreByChatSenderSince(
        @Param("chatId") chatId: UUID,
        @Param("senderId") senderId: UUID,
        @Param("sentAt") sentAt: OffsetDateTime,
        @Param("currentPromptSnapshotId") currentPromptSnapshotId: UUID,
        @Param("directQuestionReplyMultiplier") directQuestionReplyMultiplier: Int
    ): Long
}
