package com.reals.backend.repository.matching

internal object MatchmakingSqlFragments {

    const val SELECT_CANDIDATE_PAIRS_AND_BASE_JOINS = """
        SELECT
            qa.user_id AS user_a_id,
            qb.user_id AS user_b_id,
            qa.latitude AS user_a_latitude,
            qa.longitude AS user_a_longitude,
            qb.latitude AS user_b_latitude,
            qb.longitude AS user_b_longitude
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
    """

    const val BASE_COMPATIBILITY_FILTERS = """
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
    """

    const val QUEUE_BLOCK_EXCLUSION = """
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
    """

    const val QUEUE_ACTIVE_MATCH_EXCLUSION = """
            AND NOT EXISTS (
                SELECT 1
                FROM matches m
                WHERE (
                    (
                        m.user_a_id = qa.user_id
                        AND m.user_b_id = qb.user_id
                    ) OR (
                        m.user_a_id = qb.user_id
                        AND m.user_b_id = qa.user_id
                    )
                )
                AND (
                    m.state IN ('CHAT_ACTIVE', 'VISUAL_PHASE')
                    -- VISUAL_APPROVED without a connection is an active transition gap.
                    OR (
                        m.state = 'VISUAL_APPROVED'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM connections c_approved
                            WHERE c_approved.match_id = m.id
                        )
                    )
                )
            )
    """

    const val QUEUE_ACTIVE_OR_HISTORICAL_MATCH_EXCLUSION = """
            AND NOT EXISTS (
                SELECT 1
                FROM matches m
                WHERE (
                    (
                        m.user_a_id = qa.user_id
                        AND m.user_b_id = qb.user_id
                    ) OR (
                        m.user_a_id = qb.user_id
                        AND m.user_b_id = qa.user_id
                    )
                )
                AND (
                    m.state IN ('CHAT_ACTIVE', 'VISUAL_PHASE')
                    -- VISUAL_APPROVED without a connection is an active transition gap.
                    OR (
                        m.state = 'VISUAL_APPROVED'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM connections c_approved
                            WHERE c_approved.match_id = m.id
                        )
                    )
                    OR (
                        m.state IN ('CHAT_REJECTED', 'VISUAL_REJECTED')
                        AND m.updated_at > :previousPairingCutoff
                    )
                    OR (
                        m.state = 'EXPIRED'
                        AND m.updated_at > :previousPairingCutoff
                        -- VisualReview presence distinguishes visual expiration from first-chat expiration.
                        AND EXISTS (
                            SELECT 1
                            FROM visual_reviews vr_history
                            WHERE vr_history.match_id = m.id
                        )
                    )
                    OR (
                        m.state = 'EXPIRED'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM visual_reviews vr_first_chat
                            WHERE vr_first_chat.match_id = m.id
                        )
                        -- Prefer Chat.endedAt; fallback preserves legacy safety-net Match.updatedAt rows.
                        AND COALESCE(
                            (
                                SELECT fc.ended_at
                                FROM chats fc
                                WHERE fc.match_id = m.id
                                    AND fc.chat_type = 'FIRST_CHAT'
                                    AND (
                                        (
                                            fc.status = 'EXPIRED'
                                            AND fc.ended_reason = 'ABSOLUTE_TIMEOUT'
                                        ) OR (
                                            fc.status = 'ABANDONED'
                                            AND fc.ended_reason = 'INACTIVITY_TIMEOUT'
                                        )
                                    )
                            ),
                            m.updated_at
                        ) > :firstChatExpirationCutoff
                    )
                )
            )
    """

    const val QUEUE_ACTIVE_CONNECTION_EXCLUSION = """
            AND NOT EXISTS (
                SELECT 1
                FROM connections c
                WHERE (
                    (
                        c.user_a_id = qa.user_id
                        AND c.user_b_id = qb.user_id
                    ) OR (
                        c.user_a_id = qb.user_id
                        AND c.user_b_id = qa.user_id
                    )
                )
                AND c.state <> 'CLOSED'
            )
    """

    const val QUEUE_ACTIVE_OR_HISTORICAL_CONNECTION_EXCLUSION = """
            AND NOT EXISTS (
                SELECT 1
                FROM connections c
                WHERE (
                    (
                        c.user_a_id = qa.user_id
                        AND c.user_b_id = qb.user_id
                    ) OR (
                        c.user_a_id = qb.user_id
                        AND c.user_b_id = qa.user_id
                    )
                )
                AND (
                    c.state <> 'CLOSED'
                    OR (
                        c.state = 'CLOSED'
                        AND c.updated_at > :previousPairingCutoff
                    )
                )
            )
    """

    const val PROFILE_COMPATIBILITY_FILTERS = """
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
    """

    const val ORDER_LIMIT_AND_LOCK = """
        -- Eligibility filters intentionally run before LIMIT. PR2 will reduce the current broad lock scope.
        ORDER BY qa.entered_at, qb.entered_at, qa.id, qb.id
        LIMIT :limit
        FOR UPDATE OF qa, qb SKIP LOCKED
    """

    const val ACTIVE_MATCH_EXISTS = """
        EXISTS (
            SELECT 1
            FROM matches m
            WHERE (
                (
                    m.user_a_id = :userAId
                    AND m.user_b_id = :userBId
                ) OR (
                    m.user_a_id = :userBId
                    AND m.user_b_id = :userAId
                )
            )
            AND (
                m.state IN ('CHAT_ACTIVE', 'VISUAL_PHASE')
                -- VISUAL_APPROVED without a connection is an active transition gap.
                OR (
                    m.state = 'VISUAL_APPROVED'
                    AND NOT EXISTS (
                        SELECT 1
                        FROM connections c_approved
                        WHERE c_approved.match_id = m.id
                    )
                )
            )
        )
    """

    const val ACTIVE_CONNECTION_EXISTS = """
        EXISTS (
            SELECT 1
            FROM connections c
            WHERE (
                (
                    c.user_a_id = :userAId
                    AND c.user_b_id = :userBId
                ) OR (
                    c.user_a_id = :userBId
                    AND c.user_b_id = :userAId
                )
            )
            AND c.state <> 'CLOSED'
        )
    """

    const val HISTORICAL_MATCH_EXISTS = """
        EXISTS (
            SELECT 1
            FROM matches m
            WHERE (
                (
                    m.user_a_id = :userAId
                    AND m.user_b_id = :userBId
                ) OR (
                    m.user_a_id = :userBId
                    AND m.user_b_id = :userAId
                )
            )
            AND (
                (
                    m.state IN ('CHAT_REJECTED', 'VISUAL_REJECTED')
                    AND m.updated_at > :previousPairingCutoff
                )
                OR (
                    m.state = 'EXPIRED'
                    AND m.updated_at > :previousPairingCutoff
                    -- VisualReview presence distinguishes visual expiration from first-chat expiration.
                    AND EXISTS (
                        SELECT 1
                        FROM visual_reviews vr_history
                        WHERE vr_history.match_id = m.id
                    )
                )
                OR (
                    m.state = 'EXPIRED'
                    AND NOT EXISTS (
                        SELECT 1
                        FROM visual_reviews vr_first_chat
                        WHERE vr_first_chat.match_id = m.id
                    )
                    -- Prefer Chat.endedAt; fallback preserves legacy safety-net Match.updatedAt rows.
                    AND COALESCE(
                        (
                            SELECT fc.ended_at
                            FROM chats fc
                            WHERE fc.match_id = m.id
                                AND fc.chat_type = 'FIRST_CHAT'
                                AND (
                                    (
                                        fc.status = 'EXPIRED'
                                        AND fc.ended_reason = 'ABSOLUTE_TIMEOUT'
                                    ) OR (
                                        fc.status = 'ABANDONED'
                                        AND fc.ended_reason = 'INACTIVITY_TIMEOUT'
                                    )
                                )
                        ),
                        m.updated_at
                    ) > :firstChatExpirationCutoff
                )
            )
        )
    """

    const val HISTORICAL_CONNECTION_EXISTS = """
        EXISTS (
            SELECT 1
            FROM connections c
            WHERE (
                (
                    c.user_a_id = :userAId
                    AND c.user_b_id = :userBId
                ) OR (
                    c.user_a_id = :userBId
                    AND c.user_b_id = :userAId
                )
            )
            AND c.state = 'CLOSED'
            AND c.updated_at > :previousPairingCutoff
        )
    """
}
