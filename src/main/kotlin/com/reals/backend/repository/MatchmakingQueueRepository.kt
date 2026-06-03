package com.reals.backend.repository

import com.reals.backend.domain.MatchmakingQueueEntry
import com.reals.backend.repository.projection.MatchmakingCandidatePairProjection
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface MatchmakingQueueRepository :
    JpaRepository<MatchmakingQueueEntry, UUID> {

    fun existsByUserId(userId: UUID): Boolean

    fun findByUserId(userId: UUID): MatchmakingQueueEntry?

    fun deleteByUserId(userId: UUID)

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
            JOIN profiles pa
                ON pa.user_id = qa.user_id
            JOIN matchmaking_queue qb
                ON qb.status = 'WAITING'
                AND (
                    qb.entered_at > qa.entered_at
                    OR (qb.entered_at = qa.entered_at AND qb.id > qa.id)
                )
            JOIN profiles pb
                ON pb.user_id = qb.user_id
            WHERE qa.status = 'WAITING'
                AND pa.status = 'ACTIVE'
                AND pb.status = 'ACTIVE'
                AND pa.intention = pb.intention
                AND (
                    pb.looking_for_gender = 'EVERYONE'
                    OR (pb.looking_for_gender = 'MEN' AND pa.gender = 'MALE')
                    OR (pb.looking_for_gender = 'WOMEN' AND pa.gender = 'FEMALE')
                    OR (pb.looking_for_gender = 'OTHER' AND pa.gender IN ('NON_BINARY', 'OTHER'))
                )
                AND (
                    pa.looking_for_gender = 'EVERYONE'
                    OR (pa.looking_for_gender = 'MEN' AND pb.gender = 'MALE')
                    OR (pa.looking_for_gender = 'WOMEN' AND pb.gender = 'FEMALE')
                    OR (pa.looking_for_gender = 'OTHER' AND pb.gender IN ('NON_BINARY', 'OTHER'))
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
