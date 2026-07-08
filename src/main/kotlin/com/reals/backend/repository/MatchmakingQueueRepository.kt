package com.reals.backend.repository

import com.reals.backend.domain.MatchmakingQueueEntry
import com.reals.backend.domain.QueueStatus
import com.reals.backend.repository.projection.MatchmakingCandidatePairProjection
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface MatchmakingQueueRepository :
    JpaRepository<MatchmakingQueueEntry, UUID> {

    fun existsByUserId(userId: UUID): Boolean

    fun findByUserId(userId: UUID): MatchmakingQueueEntry?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MatchmakingQueueEntry q where q.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: UUID): Int

    fun countByStatus(status: QueueStatus): Long

    fun findFirstByStatusOrderByEnteredAtAsc(status: QueueStatus): MatchmakingQueueEntry?

    @Query(
        value = """
        SELECT
            CAST(qa.user_id AS VARCHAR) AS "userAId",
            CAST(qb.user_id AS VARCHAR) AS "userBId",
            qa.latitude AS "userALatitude",
            qa.longitude AS "userALongitude",
            qb.latitude AS "userBLatitude",
            qb.longitude AS "userBLongitude"
        FROM matchmaking_queue qa
        JOIN users ua
            ON ua.id = qa.user_id
        JOIN profiles pa
            ON pa.user_id = qa.user_id
        JOIN matchmaking_queue qb
            ON qb.status = 'WAITING'
            AND (
                qb.entered_at > qa.entered_at
                OR (qb.entered_at = qa.entered_at AND qb.id > qa.id)
            )
        JOIN users ub
            ON ub.id = qb.user_id
        JOIN profiles pb
            ON pb.user_id = qb.user_id
        WHERE qa.status = 'WAITING'
            AND ua.status = 'ACTIVE'
            AND ub.status = 'ACTIVE'
            AND NOT EXISTS (
                SELECT 1
                FROM penalties p
                WHERE p.user_id = qa.user_id
                    AND p.active = true
            )
            AND NOT EXISTS (
                SELECT 1
                FROM penalties p
                WHERE p.user_id = qb.user_id
                    AND p.active = true
            )
            AND NOT EXISTS (
                SELECT 1
                FROM user_blocks ub
                WHERE (
                    ub.blocker_user_id = qa.user_id
                    AND ub.blocked_user_id = qb.user_id
                ) OR (
                    ub.blocker_user_id = qb.user_id
                    AND ub.blocked_user_id = qa.user_id
                )
            )
            AND pa.status = 'ACTIVE'
            AND pb.status = 'ACTIVE'
            AND pa.intention = pb.intention
            AND EXISTS (
                SELECT 1
                FROM profile_looking_for_genders pfg_b
                WHERE pfg_b.profile_id = pb.id
                    AND pfg_b.gender = pa.gender
            )
            AND EXISTS (
                SELECT 1
                FROM profile_looking_for_genders pfg_a
                WHERE pfg_a.profile_id = pa.id
                    AND pfg_a.gender = pb.gender
            )
            AND (
                EXTRACT(YEAR FROM CAST(:today AS DATE)) - EXTRACT(YEAR FROM pb.birth_date) -
                CASE
                    WHEN EXTRACT(MONTH FROM CAST(:today AS DATE)) < EXTRACT(MONTH FROM pb.birth_date)
                        OR (
                            EXTRACT(MONTH FROM CAST(:today AS DATE)) = EXTRACT(MONTH FROM pb.birth_date)
                            AND EXTRACT(DAY FROM CAST(:today AS DATE)) < EXTRACT(DAY FROM pb.birth_date)
                        )
                    THEN 1
                    ELSE 0
                END
            ) BETWEEN pa.preferred_min_age AND pa.preferred_max_age
            AND (
                EXTRACT(YEAR FROM CAST(:today AS DATE)) - EXTRACT(YEAR FROM pa.birth_date) -
                CASE
                    WHEN EXTRACT(MONTH FROM CAST(:today AS DATE)) < EXTRACT(MONTH FROM pa.birth_date)
                        OR (
                            EXTRACT(MONTH FROM CAST(:today AS DATE)) = EXTRACT(MONTH FROM pa.birth_date)
                            AND EXTRACT(DAY FROM CAST(:today AS DATE)) < EXTRACT(DAY FROM pa.birth_date)
                        )
                    THEN 1
                    ELSE 0
                END
            ) BETWEEN pb.preferred_min_age AND pb.preferred_max_age
        ORDER BY qa.entered_at, qb.entered_at, qa.id, qb.id
        LIMIT :limit
        FOR UPDATE OF qa, qb SKIP LOCKED
    """,
        nativeQuery = true
    )
    fun findBasicCompatiblePairsSkipLocked(
        @Param("limit")
        limit: Int,
        @Param("today")
        today: LocalDate
    ): List<MatchmakingCandidatePairProjection>
}
