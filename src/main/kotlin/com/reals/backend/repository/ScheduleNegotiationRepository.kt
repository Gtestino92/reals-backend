package com.reals.backend.repository

import com.reals.backend.domain.ScheduleNegotiation
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface ScheduleNegotiationRepository :
    JpaRepository<ScheduleNegotiation, UUID> {

    fun findByConnectionId(
        connectionId: UUID
    ): ScheduleNegotiation?

    fun findByConnectionIdIn(
        connectionIds: Collection<UUID>
    ): List<ScheduleNegotiation>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update ScheduleNegotiation n
        set n.status = :failedStatus,
            n.updatedAt = :updatedAt
        where n.connectionId in :connectionIds
          and n.status = :pendingStatus
        """
    )
    fun failPendingByConnectionIds(
        @Param("connectionIds") connectionIds: Collection<UUID>,
        @Param("updatedAt") updatedAt: OffsetDateTime,
        @Param("failedStatus") failedStatus: NegotiationStatus = NegotiationStatus.FAILED,
        @Param("pendingStatus") pendingStatus: NegotiationStatus = NegotiationStatus.PENDING
    ): Int

    @Query(
        """select n from ScheduleNegotiation n
           , Connection c
           where c.id = n.connectionId
             and c.state = :connectionState
             and n.status = :status
             and n.confirmedDateTime is not null
             and n.confirmedDateTime <= :expiresBefore
             and not exists (
                 select chat.id from Chat chat
                 where chat.connectionId = c.id
                   and chat.chatType = 'SECOND_CHAT'
             )"""
    )
    fun findExpiredConfirmedScheduledNegotiationsWithoutSecondChat(
        @Param("status") status: NegotiationStatus = NegotiationStatus.CONFIRMED,
        @Param("connectionState") connectionState: ConnectionState = ConnectionState.SECOND_CHAT_SCHEDULED,
        @Param("expiresBefore") expiresBefore: OffsetDateTime
    ): List<ScheduleNegotiation>

    @Query(
        """select n from ScheduleNegotiation n
           , Connection c
           where c.id = n.connectionId
             and c.state in :states
             and n.status = :status
             and n.confirmedDateTime is not null
             and n.confirmedDateTime >= :windowStart
             and n.confirmedDateTime <= :windowEnd"""
    )
    fun findConfirmedSecondChatReminderDueForWindow(
        @Param("windowStart") windowStart: OffsetDateTime,
        @Param("windowEnd") windowEnd: OffsetDateTime,
        @Param("status") status: NegotiationStatus = NegotiationStatus.CONFIRMED,
        @Param("states") states: Collection<ConnectionState> = listOf(
            ConnectionState.SECOND_CHAT_SCHEDULED
        )
    ): List<ScheduleNegotiation>

    @Query(
        """select n from ScheduleNegotiation n
           , Connection c
           where c.id = n.connectionId
             and c.state in :states
             and n.status = :status
             and n.confirmedDateTime is not null
             and n.confirmedDateTime <= :dueBefore"""
    )
    fun findConfirmedSecondChatNoShowDue(
        @Param("dueBefore") dueBefore: OffsetDateTime,
        @Param("status") status: NegotiationStatus = NegotiationStatus.CONFIRMED,
        @Param("states") states: Collection<ConnectionState> = listOf(
            ConnectionState.SECOND_CHAT_SCHEDULED,
            ConnectionState.SECOND_CHAT_AVAILABLE,
            ConnectionState.SECOND_CHAT
        )
    ): List<ScheduleNegotiation>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """update ScheduleNegotiation n
           set n.confirmedDateTime = :confirmedDateTime,
               n.updatedAt = :updatedAt
           where n.connectionId = :connectionId"""
    )
    fun updateConfirmedDateTimeByConnectionId(
        @Param("connectionId") connectionId: UUID,
        @Param("confirmedDateTime") confirmedDateTime: OffsetDateTime,
        @Param("updatedAt") updatedAt: OffsetDateTime = OffsetDateTime.now()
    ): Int
}
