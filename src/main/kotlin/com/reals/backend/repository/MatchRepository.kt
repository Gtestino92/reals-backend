package com.reals.backend.repository

import com.reals.backend.domain.*
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface MatchRepository :
    JpaRepository<Match, UUID> {

    fun findByStateAndCreatedAtBefore(
        state: MatchState,
        createdAtBefore: OffsetDateTime
    ): List<Match>

    @Query(
        """
        select m.id from Match m
        where m.state = :state
          and m.createdAt < :createdAtBefore
        order by m.createdAt asc, m.id asc
        """
    )
    fun findIdsByStateAndCreatedAtBefore(
        @Param("state") state: MatchState,
        @Param("createdAtBefore") createdAtBefore: OffsetDateTime,
        pageable: Pageable
    ): List<UUID>

    @Query(
        """
        select m from Match m
        where (m.userAId = :userId or m.userBId = :userId)
          and m.state in :states
        """
    )
    fun findByParticipantIdAndStateIn(
        @Param("userId") userId: UUID,
        @Param("states") states: Collection<MatchState>
    ): List<Match>

    @Query(
        """
        select m from Match m
        where ((m.userAId = :userAId and m.userBId = :userBId)
            or (m.userAId = :userBId and m.userBId = :userAId))
          and m.state in :states
        """
    )
    fun findBetweenUsersAndStateIn(
        @Param("userAId") userAId: UUID,
        @Param("userBId") userBId: UUID,
        @Param("states") states: Collection<MatchState>
    ): List<Match>
}
