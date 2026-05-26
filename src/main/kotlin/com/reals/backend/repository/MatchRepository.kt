package com.reals.backend.repository

import com.reals.backend.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime
import java.util.UUID

interface MatchRepository :
    JpaRepository<Match, UUID> {

    fun findByStateAndCreatedAtBefore(
        state: MatchState,
        createdAtBefore: OffsetDateTime
    ): List<Match>
}
