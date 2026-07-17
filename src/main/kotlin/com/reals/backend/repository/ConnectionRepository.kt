package com.reals.backend.repository

import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

interface ConnectionRepository :
    JpaRepository<Connection, UUID> {

    fun findByMatchId(
        matchId: UUID
    ): Connection?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Connection c where c.id = :connectionId")
    fun findByIdForUpdate(
        @Param("connectionId") connectionId: UUID
    ): Connection?

    fun findByStateAndSchedulingExpiresAtBefore(
        state: ConnectionState,
        before: OffsetDateTime
    ): List<Connection>

    @Query(
        """
        select c from Connection c
        where c.schedulingAvailableAt is not null
          and c.schedulingAvailableAt <= :now
          and (
            c.state = :pendingState
            or (
              c.state = :phaseState
              and not exists (
                select n.id from ScheduleNegotiation n
                where n.connectionId = c.id
              )
            )
          )
        """
    )
    fun findSchedulingActivationDue(
        @Param("pendingState") pendingState: ConnectionState = ConnectionState.SCHEDULING_PENDING,
        @Param("phaseState") phaseState: ConnectionState = ConnectionState.SCHEDULING_PHASE,
        @Param("now") now: OffsetDateTime
    ): List<Connection>

    @Query(
        """
        select c.id from Connection c
        where c.schedulingAvailableAt is not null
          and c.schedulingAvailableAt <= :now
          and (
            c.state = :pendingState
            or (
              c.state = :phaseState
              and not exists (
                select n.id from ScheduleNegotiation n
                where n.connectionId = c.id
              )
            )
          )
        order by c.schedulingAvailableAt asc, c.id asc
        """
    )
    fun findSchedulingActivationDueIds(
        @Param("pendingState") pendingState: ConnectionState = ConnectionState.SCHEDULING_PENDING,
        @Param("phaseState") phaseState: ConnectionState = ConnectionState.SCHEDULING_PHASE,
        @Param("now") now: OffsetDateTime,
        pageable: Pageable
    ): List<UUID>

    @Query(
        """
        select c from Connection c
        where (c.userAId = :userId or c.userBId = :userId)
          and c.state in :states
        """
    )
    fun findByParticipantIdAndStateIn(
        @Param("userId") userId: UUID,
        @Param("states") states: Collection<ConnectionState>
    ): List<Connection>

    @Query(
        """
        select c from Connection c
        where ((c.userAId = :userAId and c.userBId = :userBId)
            or (c.userAId = :userBId and c.userBId = :userAId))
          and c.state in :states
        """
    )
    fun findBetweenUsersAndStateIn(
        @Param("userAId") userAId: UUID,
        @Param("userBId") userBId: UUID,
        @Param("states") states: Collection<ConnectionState>
    ): List<Connection>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Connection c set c.schedulingExpiresAt = :expiresAt where c.id = :connectionId")
    fun updateSchedulingExpiresAt(
        @Param("connectionId") connectionId: UUID,
        @Param("expiresAt") expiresAt: OffsetDateTime
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Connection c set c.schedulingAvailableAt = :availableAt where c.id = :connectionId")
    fun updateSchedulingAvailableAt(
        @Param("connectionId") connectionId: UUID,
        @Param("availableAt") availableAt: OffsetDateTime
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update Connection c
        set c.state = :targetState,
            c.updatedAt = :updatedAt
        where c.id = :connectionId
          and c.state in :allowedStates
        """
    )
    fun transitionToSecondChatIfAllowed(
        @Param("connectionId") connectionId: UUID,
        @Param("updatedAt") updatedAt: OffsetDateTime,
        @Param("targetState") targetState: ConnectionState = ConnectionState.SECOND_CHAT,
        @Param("allowedStates") allowedStates: Collection<ConnectionState> = listOf(
            ConnectionState.SECOND_CHAT_SCHEDULED,
            ConnectionState.SECOND_CHAT_AVAILABLE
        )
    ): Int
}
