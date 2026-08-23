package com.reals.backend.repository

import com.reals.backend.domain.MatchmakingAvailabilityNotificationEpisode
import com.reals.backend.domain.MatchmakingAvailabilityNotificationEpisodeStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface MatchmakingAvailabilityNotificationEpisodeRepository :
    JpaRepository<MatchmakingAvailabilityNotificationEpisode, UUID> {

    fun findByUserIdAndStatus(
        userId: UUID,
        status: MatchmakingAvailabilityNotificationEpisodeStatus
    ): MatchmakingAvailabilityNotificationEpisode?

    fun countByUserIdAndStatus(
        userId: UUID,
        status: MatchmakingAvailabilityNotificationEpisodeStatus
    ): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select e
        from MatchmakingAvailabilityNotificationEpisode e
        where e.userId = :userId
          and e.status = :status
        """
    )
    fun findByUserIdAndStatusForUpdate(
        @Param("userId") userId: UUID,
        @Param("status") status: MatchmakingAvailabilityNotificationEpisodeStatus
    ): MatchmakingAvailabilityNotificationEpisode?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select e
        from MatchmakingAvailabilityNotificationEpisode e
        where e.id = :id
        """
    )
    fun findByIdForUpdate(
        @Param("id") id: UUID
    ): MatchmakingAvailabilityNotificationEpisode?

    @Query(
        """
        select e.id
        from MatchmakingAvailabilityNotificationEpisode e
        where e.status = :status
          and e.nextCheckAt <= :now
        order by e.nextCheckAt asc, e.id asc
        """
    )
    fun findDueEpisodeIds(
        @Param("status") status: MatchmakingAvailabilityNotificationEpisodeStatus,
        @Param("now") now: OffsetDateTime,
        pageable: Pageable
    ): List<UUID>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MatchmakingAvailabilityNotificationEpisode e where e.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: UUID): Int

    @Query(
        value = """
        with participant_advancements as (
            select m.user_a_id as user_id, v.created_at, v.id
            from visual_reviews v
            join matches m on m.id = v.match_id
            join users u on u.id = m.user_a_id
            where v.created_at > :cutoff
              and u.status = 'ACTIVE'
            union all
            select m.user_b_id as user_id, v.created_at, v.id
            from visual_reviews v
            join matches m on m.id = v.match_id
            join users u on u.id = m.user_b_id
            where v.created_at > :cutoff
              and u.status = 'ACTIVE'
        )
        select cast(user_id as varchar)
        from participant_advancements
        group by user_id
        having count(*) >= :limit
        order by user_id
        """,
        nativeQuery = true
    )
    fun findUsersAtOrOverVisualAdvancementCap(
        @Param("cutoff") cutoff: OffsetDateTime,
        @Param("limit") limit: Int,
        pageable: Pageable
    ): List<String>

    @Query(
        value = """
        with participant_advancements as (
            select m.user_a_id as user_id, v.created_at, v.id
            from visual_reviews v
            join matches m on m.id = v.match_id
            join users u on u.id = m.user_a_id
            where v.created_at > :cutoff
              and m.user_a_id > :cursorUserId
              and u.status = 'ACTIVE'
            union all
            select m.user_b_id as user_id, v.created_at, v.id
            from visual_reviews v
            join matches m on m.id = v.match_id
            join users u on u.id = m.user_b_id
            where v.created_at > :cutoff
              and m.user_b_id > :cursorUserId
              and u.status = 'ACTIVE'
        )
        select cast(user_id as varchar)
        from participant_advancements
        group by user_id
        having count(*) >= :limit
        order by user_id
        """,
        nativeQuery = true
    )
    fun findUsersAtOrOverVisualAdvancementCapAfter(
        @Param("cutoff") cutoff: OffsetDateTime,
        @Param("limit") limit: Int,
        @Param("cursorUserId") cursorUserId: UUID,
        pageable: Pageable
    ): List<String>
}
