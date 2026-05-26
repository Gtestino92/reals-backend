package com.reals.backend.repository

import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime
import java.util.UUID

interface ConnectionRepository :
    JpaRepository<Connection, UUID> {

    fun findByMatchId(
        matchId: UUID
    ): Connection?

    fun findByStateAndSchedulingExpiresAtBefore(
        state: ConnectionState,
        before: OffsetDateTime
    ): List<Connection>
}
