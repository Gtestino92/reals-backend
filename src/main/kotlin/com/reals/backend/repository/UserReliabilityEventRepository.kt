package com.reals.backend.repository

import com.reals.backend.domain.UserReliabilityEvent
import com.reals.backend.domain.UserReliabilityEventType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface UserReliabilityEventRepository : JpaRepository<UserReliabilityEvent, UUID> {

    fun findByUserIdAndExpiresAtAfterOrderByOccurredAtDesc(
        userId: UUID,
        expiresAt: OffsetDateTime
    ): List<UserReliabilityEvent>

    fun findByUserIdInAndExpiresAtAfter(
        userIds: Collection<UUID>,
        expiresAt: OffsetDateTime
    ): List<UserReliabilityEvent>

    fun existsByUserIdAndEventTypeAndRelatedMatchId(
        userId: UUID,
        eventType: UserReliabilityEventType,
        relatedMatchId: UUID
    ): Boolean

    fun existsByUserIdAndEventTypeAndRelatedConnectionId(
        userId: UUID,
        eventType: UserReliabilityEventType,
        relatedConnectionId: UUID
    ): Boolean

    fun existsByUserIdAndEventTypeAndRelatedChatId(
        userId: UUID,
        eventType: UserReliabilityEventType,
        relatedChatId: UUID
    ): Boolean

    fun existsByUserIdAndEventTypeAndRelatedSafetyReportId(
        userId: UUID,
        eventType: UserReliabilityEventType,
        relatedSafetyReportId: UUID
    ): Boolean

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from UserReliabilityEvent e where e.expiresAt <= :now")
    fun deleteExpiredEvents(
        @Param("now") now: OffsetDateTime
    ): Int
}
