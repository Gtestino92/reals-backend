package com.reals.backend.repository

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.domain.ScheduleNegotiation
import com.reals.backend.domain.SecondChatAttendanceStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from ScheduleNegotiation n where n.connectionId = :connectionId")
    fun findByConnectionIdForUpdate(
        @Param("connectionId") connectionId: UUID
    ): ScheduleNegotiation?

    fun findByConnectionIdIn(
        connectionIds: Collection<UUID>
    ): List<ScheduleNegotiation>

    @Query(
        """select n from ScheduleNegotiation n
           , Connection c
           where c.id = n.connectionId
             and c.id <> :excludedConnectionId
             and (c.userAId in :userIds or c.userBId in :userIds)
             and c.state in :states
             and n.status = :status
             and n.confirmedDateTime is not null"""
    )
    fun findConfirmedReservedSecondChatSlotsForUsers(
        @Param("userIds") userIds: Collection<UUID>,
        @Param("excludedConnectionId") excludedConnectionId: UUID,
        @Param("states") states: Collection<ConnectionState> = listOf(
            ConnectionState.SECOND_CHAT_SCHEDULED,
            ConnectionState.SECOND_CHAT_AVAILABLE
        ),
        @Param("status") status: NegotiationStatus = NegotiationStatus.CONFIRMED
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
        """select n.connectionId from ScheduleNegotiation n
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
             )
           order by n.confirmedDateTime asc, n.id asc"""
    )
    fun findExpiredConfirmedScheduledNegotiationConnectionIdsWithoutSecondChat(
        @Param("status") status: NegotiationStatus = NegotiationStatus.CONFIRMED,
        @Param("connectionState") connectionState: ConnectionState = ConnectionState.SECOND_CHAT_SCHEDULED,
        @Param("expiresBefore") expiresBefore: OffsetDateTime,
        pageable: Pageable
    ): List<UUID>

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
             and n.confirmedDateTime >= :windowStart
             and n.confirmedDateTime <= :windowEnd
           order by n.confirmedDateTime asc, n.id asc"""
    )
    fun findConfirmedSecondChatReminderDueForWindow(
        @Param("windowStart") windowStart: OffsetDateTime,
        @Param("windowEnd") windowEnd: OffsetDateTime,
        @Param("status") status: NegotiationStatus = NegotiationStatus.CONFIRMED,
        @Param("states") states: Collection<ConnectionState> = listOf(
            ConnectionState.SECOND_CHAT_SCHEDULED
        ),
        pageable: Pageable
    ): List<ScheduleNegotiation>

    @Query(
        """select n from ScheduleNegotiation n
           , Connection c
           where c.id = n.connectionId
             and c.state in :states
             and n.status = :status
             and n.confirmedDateTime is not null
             and n.confirmedDateTime > :windowStartExclusive
             and n.confirmedDateTime <= :windowEndInclusive
           order by n.confirmedDateTime asc, n.id asc"""
    )
    fun findConfirmedSecondChatReminderRecoverableForWindow(
        @Param("windowStartExclusive") windowStartExclusive: OffsetDateTime,
        @Param("windowEndInclusive") windowEndInclusive: OffsetDateTime,
        @Param("status") status: NegotiationStatus = NegotiationStatus.CONFIRMED,
        @Param("states") states: Collection<ConnectionState> = listOf(
            ConnectionState.SECOND_CHAT_SCHEDULED
        ),
        pageable: Pageable
    ): List<ScheduleNegotiation>

    @Query(
        """select n.connectionId from ScheduleNegotiation n
           , Connection c
           where c.id = n.connectionId
             and c.state in :states
             and n.status = :status
             and n.confirmedDateTime is not null
             and n.confirmedDateTime >= :windowStartInclusive
             and n.confirmedDateTime <= :now
             and (
                (select count(p.id) from SecondChatParticipation p where p.connectionId = c.id) < 2
                or exists (
                    select p.id from SecondChatParticipation p
                    where p.connectionId = c.id
                      and p.attendanceStatus not in :joinedStatuses
                )
             )
           order by n.confirmedDateTime asc, n.id asc"""
    )
    fun findConfirmedSecondChatStartNotificationDueConnectionIds(
        @Param("windowStartInclusive") windowStartInclusive: OffsetDateTime,
        @Param("now") now: OffsetDateTime,
        @Param("status") status: NegotiationStatus = NegotiationStatus.CONFIRMED,
        @Param("states") states: Collection<ConnectionState> = listOf(
            ConnectionState.SECOND_CHAT_SCHEDULED,
            ConnectionState.SECOND_CHAT_AVAILABLE,
            ConnectionState.SECOND_CHAT
        ),
        @Param("joinedStatuses") joinedStatuses: Collection<SecondChatAttendanceStatus> = listOf(
            SecondChatAttendanceStatus.ON_TIME,
            SecondChatAttendanceStatus.LATE
        ),
        pageable: Pageable
    ): List<UUID>

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

    @Query(
        """select n.connectionId from ScheduleNegotiation n
           , Connection c
           where c.id = n.connectionId
             and c.state in :states
             and n.status = :status
             and n.confirmedDateTime is not null
             and n.confirmedDateTime <= :dueBefore
           order by n.confirmedDateTime asc, n.id asc"""
    )
    fun findConfirmedSecondChatNoShowDueConnectionIds(
        @Param("dueBefore") dueBefore: OffsetDateTime,
        @Param("status") status: NegotiationStatus = NegotiationStatus.CONFIRMED,
        @Param("states") states: Collection<ConnectionState> = listOf(
            ConnectionState.SECOND_CHAT_SCHEDULED,
            ConnectionState.SECOND_CHAT_AVAILABLE,
            ConnectionState.SECOND_CHAT
        ),
        pageable: Pageable
    ): List<UUID>

    @Query(
        """select n.connectionId from ScheduleNegotiation n
           , Connection c
           where c.id = n.connectionId
             and c.state in :states
             and n.status = :status
             and n.confirmedDateTime is not null
             and n.confirmedDateTime <= :dueBefore
             and (
                not exists (
                    select p.id from SecondChatParticipation p
                    where p.connectionId = c.id
                )
                or exists (
                    select p.id from SecondChatParticipation p
                    where p.connectionId = c.id
                      and p.attendanceStatus = 'PENDING'
                )
             )
           order by n.confirmedDateTime asc, n.id asc"""
    )
    fun findConfirmedSecondChatHardCutoffDueConnectionIds(
        @Param("dueBefore") dueBefore: OffsetDateTime,
        @Param("status") status: NegotiationStatus = NegotiationStatus.CONFIRMED,
        @Param("states") states: Collection<ConnectionState> = listOf(
            ConnectionState.SECOND_CHAT_SCHEDULED,
            ConnectionState.SECOND_CHAT_AVAILABLE,
            ConnectionState.SECOND_CHAT
        ),
        pageable: Pageable
    ): List<UUID>

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
