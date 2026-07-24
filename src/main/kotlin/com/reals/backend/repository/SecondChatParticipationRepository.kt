package com.reals.backend.repository

import com.reals.backend.domain.SecondChatAttendanceStatus
import com.reals.backend.domain.SecondChatParticipation
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SecondChatParticipationRepository : JpaRepository<SecondChatParticipation, UUID> {

    fun findByConnectionId(
        connectionId: UUID
    ): List<SecondChatParticipation>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SecondChatParticipation p where p.connectionId = :connectionId order by p.userId asc")
    fun findByConnectionIdForUpdate(
        @Param("connectionId") connectionId: UUID
    ): List<SecondChatParticipation>

    fun findByConnectionIdAndUserId(
        connectionId: UUID,
        userId: UUID
    ): SecondChatParticipation?

    fun countByConnectionIdAndAttendanceStatus(
        connectionId: UUID,
        attendanceStatus: SecondChatAttendanceStatus
    ): Long
}
