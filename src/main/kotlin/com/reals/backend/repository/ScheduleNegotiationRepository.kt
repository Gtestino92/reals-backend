package com.reals.backend.repository

import com.reals.backend.domain.ScheduleNegotiation
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

    @Query(
        """select n from ScheduleNegotiation n
           where n.status = :status
             and n.confirmedDateTime <= :now"""
    )
    fun findDueConfirmedNegotiations(
        @Param("status") status: NegotiationStatus = NegotiationStatus.CONFIRMED,
        @Param("now") now: OffsetDateTime
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
