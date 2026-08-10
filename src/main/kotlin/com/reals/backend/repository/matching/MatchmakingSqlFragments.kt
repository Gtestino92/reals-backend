package com.reals.backend.repository.matching

internal object MatchmakingSqlFragments {

    val ANCHOR_SELECT_AND_BASE_JOINS = """
        SELECT
            qa.id AS anchor_queue_entry_id,
            qa.user_id AS anchor_user_id
        FROM matchmaking_queue qa
        JOIN users ua
            ON ua.id = qa.user_id
        JOIN profiles pa
            ON pa.user_id = qa.user_id
    """

    val ANCHOR_BASE_FILTERS = """
        WHERE qa.status = 'WAITING'
            AND ua.status = 'ACTIVE'
            AND pa.status = 'ACTIVE'
            AND NOT EXISTS (
                SELECT 1
                FROM penalties p
                WHERE p.user_id = qa.user_id
                    AND p.active = true
            )
    """

    val PARTNER_LATERAL_JOIN: String
        get() = """
        -- Non-locking existence probe: old unmatchable anchors must not block later anchors.
        JOIN LATERAL (
            SELECT 1
            FROM matchmaking_queue qb
            JOIN users ub
                ON ub.id = qb.user_id
            JOIN profiles pb
                ON pb.user_id = qb.user_id
            WHERE qb.status = 'WAITING'
                AND ${PARTNER_AFTER_ANCHOR_CONDITION}
                ${PARTNER_BASE_FILTERS}
                ${PAIR_BLOCK_EXCLUSION}
                ${PAIR_ACTIVE_MATCH_EXCLUSION}
                ${PAIR_ACTIVE_CONNECTION_EXCLUSION}
                ${PROFILE_COMPATIBILITY_FILTERS}
                ${MUTUAL_DISTANCE_FILTER}
            ORDER BY qb.entered_at, qb.id
            LIMIT 1
        ) partner_probe ON true
    """

    val PARTNER_LATERAL_JOIN_WITH_HISTORY: String
        get() = """
        -- Non-locking existence probe: old unmatchable anchors must not block later anchors.
        JOIN LATERAL (
            SELECT 1
            FROM matchmaking_queue qb
            JOIN users ub
                ON ub.id = qb.user_id
            JOIN profiles pb
                ON pb.user_id = qb.user_id
            WHERE qb.status = 'WAITING'
                AND ${PARTNER_AFTER_ANCHOR_CONDITION}
                ${PARTNER_BASE_FILTERS}
                ${PAIR_BLOCK_EXCLUSION}
                ${PAIR_ACTIVE_OR_HISTORICAL_MATCH_EXCLUSION}
                ${PAIR_ACTIVE_OR_HISTORICAL_CONNECTION_EXCLUSION}
                ${PROFILE_COMPATIBILITY_FILTERS}
                ${MUTUAL_DISTANCE_FILTER}
            ORDER BY qb.entered_at, qb.id
            LIMIT 1
        ) partner_probe ON true
    """

    fun partnerLateralJoin(
        pairMatchExclusion: String,
        pairConnectionExclusion: String
    ): String =
        """
        -- Non-locking existence probe: old unmatchable anchors must not block later anchors.
        JOIN LATERAL (
            SELECT 1
            FROM matchmaking_queue qb
            JOIN users ub
                ON ub.id = qb.user_id
            JOIN profiles pb
                ON pb.user_id = qb.user_id
            WHERE qb.status = 'WAITING'
                AND ${PARTNER_AFTER_ANCHOR_CONDITION}
                ${PARTNER_BASE_FILTERS}
                ${PAIR_BLOCK_EXCLUSION}
                $pairMatchExclusion
                $pairConnectionExclusion
                ${PROFILE_COMPATIBILITY_FILTERS}
                ${MUTUAL_DISTANCE_FILTER}
            ORDER BY qb.entered_at, qb.id
            LIMIT 1
        ) partner_probe ON true
        """.trimIndent()

    val PARTNER_EXISTS_FILTER: String
        get() = """
            AND EXISTS (
                SELECT 1
                FROM matchmaking_queue qb
                JOIN users ub
                    ON ub.id = qb.user_id
                JOIN profiles pb
                    ON pb.user_id = qb.user_id
                WHERE qb.status = 'WAITING'
                    AND ${PARTNER_AFTER_ANCHOR_CONDITION}
                    ${PARTNER_BASE_FILTERS}
                    ${PAIR_BLOCK_EXCLUSION}
                    ${PAIR_ACTIVE_MATCH_EXCLUSION}
                    ${PAIR_ACTIVE_CONNECTION_EXCLUSION}
                    ${PROFILE_COMPATIBILITY_FILTERS}
                    ${MUTUAL_DISTANCE_FILTER}
            )
    """

    val PARTNER_EXISTS_FILTER_WITH_HISTORY: String
        get() = """
            AND EXISTS (
                SELECT 1
                FROM matchmaking_queue qb
                JOIN users ub
                    ON ub.id = qb.user_id
                JOIN profiles pb
                    ON pb.user_id = qb.user_id
                WHERE qb.status = 'WAITING'
                    AND ${PARTNER_AFTER_ANCHOR_CONDITION}
                    ${PARTNER_BASE_FILTERS}
                    ${PAIR_BLOCK_EXCLUSION}
                    ${PAIR_ACTIVE_OR_HISTORICAL_MATCH_EXCLUSION}
                    ${PAIR_ACTIVE_OR_HISTORICAL_CONNECTION_EXCLUSION}
                    ${PROFILE_COMPATIBILITY_FILTERS}
                    ${MUTUAL_DISTANCE_FILTER}
            )
    """

    fun partnerExistsFilter(
        pairMatchExclusion: String,
        pairConnectionExclusion: String
    ): String =
        """
            AND EXISTS (
                SELECT 1
                FROM matchmaking_queue qb
                JOIN users ub
                    ON ub.id = qb.user_id
                JOIN profiles pb
                    ON pb.user_id = qb.user_id
                WHERE qb.status = 'WAITING'
                    AND ${PARTNER_AFTER_ANCHOR_CONDITION}
                    ${PARTNER_BASE_FILTERS}
                    ${PAIR_BLOCK_EXCLUSION}
                    $pairMatchExclusion
                    $pairConnectionExclusion
                    ${PROFILE_COMPATIBILITY_FILTERS}
                    ${MUTUAL_DISTANCE_FILTER}
            )
        """.trimIndent()

    val PARTNER_SELECT_AND_BASE_JOINS = """
        SELECT
            qb.id AS partner_queue_entry_id,
            qb.entered_at AS partner_entered_at,
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
        JOIN users ub
            ON ub.id = qb.user_id
        JOIN profiles pb
            ON pb.user_id = qb.user_id
    """

    val PARTNER_DISCOVERY_BASE_FILTERS: String
        get() = """
        WHERE qa.id = :anchorQueueEntryId
            AND qa.status = 'WAITING'
            AND ua.status = 'ACTIVE'
            AND pa.status = 'ACTIVE'
            AND NOT EXISTS (
                SELECT 1
                FROM penalties p
                WHERE p.user_id = qa.user_id
                    AND p.active = true
            )
            AND ${PARTNER_AFTER_ANCHOR_CONDITION}
    """

    val PARTNER_CLAIM_BASE_FILTERS: String
        get() = """
        WHERE qa.id = :anchorQueueEntryId
            AND qb.id = :partnerQueueEntryId
            AND qa.status = 'WAITING'
            AND ua.status = 'ACTIVE'
            AND pa.status = 'ACTIVE'
            AND NOT EXISTS (
                SELECT 1
                FROM penalties p
                WHERE p.user_id = qa.user_id
                    AND p.active = true
            )
            AND ${PARTNER_AFTER_ANCHOR_CONDITION}
    """

    val PARTNER_AFTER_ANCHOR_CONDITION = """
        (
            qb.entered_at > qa.entered_at
            OR (
                qb.entered_at = qa.entered_at
                AND qb.id > qa.id
            )
        )
    """

    val PARTNER_BASE_FILTERS = """
            AND ub.status = 'ACTIVE'
            AND pb.status = 'ACTIVE'
            AND NOT EXISTS (
                SELECT 1
                FROM penalties p
                WHERE p.user_id = qb.user_id
                    AND p.active = true
            )
    """

    val PAIR_BLOCK_EXCLUSION = """
            AND NOT EXISTS (
                SELECT 1
                FROM user_blocks user_block
                WHERE (
                    user_block.blocker_user_id = qa.user_id
                    AND user_block.blocked_user_id = qb.user_id
                ) OR (
                    user_block.blocker_user_id = qb.user_id
                    AND user_block.blocked_user_id = qa.user_id
                )
            )
    """

    val PAIR_ACTIVE_MATCH_EXCLUSION: String
        get() = """
            AND NOT EXISTS (
                SELECT 1
                FROM matches m
                WHERE ${UNORDERED_QUEUE_PAIR_MATCH}
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

    val PAIR_ACTIVE_OR_HISTORICAL_MATCH_EXCLUSION: String
        get() = """
            AND NOT EXISTS (
                SELECT 1
                FROM matches m
                WHERE ${UNORDERED_QUEUE_PAIR_MATCH}
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
                        m.state = 'CHAT_REJECTED'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM chats fc_mismatch
                            WHERE fc_mismatch.match_id = m.id
                                AND fc_mismatch.chat_type = 'FIRST_CHAT'
                                AND fc_mismatch.ended_reason = 'FIRST_CHAT_DECISION_MISMATCH'
                        )
                        AND m.updated_at > :previousPairingCutoff
                    )
                    OR (
                        m.state = 'CHAT_REJECTED'
                        AND EXISTS (
                            SELECT 1
                            FROM chats fc_mismatch
                            WHERE fc_mismatch.match_id = m.id
                                AND fc_mismatch.chat_type = 'FIRST_CHAT'
                                AND fc_mismatch.ended_reason = 'FIRST_CHAT_DECISION_MISMATCH'
                        )
                        AND COALESCE(
                            (
                                SELECT fc_mismatch.ended_at
                                FROM chats fc_mismatch
                                WHERE fc_mismatch.match_id = m.id
                                    AND fc_mismatch.chat_type = 'FIRST_CHAT'
                                    AND fc_mismatch.ended_reason = 'FIRST_CHAT_DECISION_MISMATCH'
                            ),
                            m.updated_at
                        ) > :firstChatDecisionMismatchCutoff
                    )
                    OR (
                        m.state = 'VISUAL_REJECTED'
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

    val PAIR_HISTORICAL_MATCH_EXCLUSION: String
        get() = """
            AND NOT EXISTS (
                SELECT 1
                FROM matches m
                WHERE ${UNORDERED_QUEUE_PAIR_MATCH}
                AND (
                    (
                        m.state = 'CHAT_REJECTED'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM chats fc_mismatch
                            WHERE fc_mismatch.match_id = m.id
                                AND fc_mismatch.chat_type = 'FIRST_CHAT'
                                AND fc_mismatch.ended_reason = 'FIRST_CHAT_DECISION_MISMATCH'
                        )
                        AND m.updated_at > :previousPairingCutoff
                    )
                    OR (
                        m.state = 'CHAT_REJECTED'
                        AND EXISTS (
                            SELECT 1
                            FROM chats fc_mismatch
                            WHERE fc_mismatch.match_id = m.id
                                AND fc_mismatch.chat_type = 'FIRST_CHAT'
                                AND fc_mismatch.ended_reason = 'FIRST_CHAT_DECISION_MISMATCH'
                        )
                        AND COALESCE(
                            (
                                SELECT fc_mismatch.ended_at
                                FROM chats fc_mismatch
                                WHERE fc_mismatch.match_id = m.id
                                    AND fc_mismatch.chat_type = 'FIRST_CHAT'
                                    AND fc_mismatch.ended_reason = 'FIRST_CHAT_DECISION_MISMATCH'
                            ),
                            m.updated_at
                        ) > :firstChatDecisionMismatchCutoff
                    )
                    OR (
                        m.state = 'VISUAL_REJECTED'
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

    val PAIR_ACTIVE_CONNECTION_EXCLUSION: String
        get() = """
            AND NOT EXISTS (
                SELECT 1
                FROM connections c
                WHERE ${UNORDERED_QUEUE_PAIR_CONNECTION}
                AND c.state <> 'CLOSED'
            )
    """

    val PAIR_ACTIVE_OR_HISTORICAL_CONNECTION_EXCLUSION: String
        get() = """
            AND NOT EXISTS (
                SELECT 1
                FROM connections c
                WHERE ${UNORDERED_QUEUE_PAIR_CONNECTION}
                AND (
                    c.state <> 'CLOSED'
                    OR (
                        c.state = 'CLOSED'
                        AND c.updated_at > :previousPairingCutoff
                    )
                )
            )
    """

    val PAIR_HISTORICAL_CONNECTION_EXCLUSION: String
        get() = """
            AND NOT EXISTS (
                SELECT 1
                FROM connections c
                WHERE ${UNORDERED_QUEUE_PAIR_CONNECTION}
                AND c.state = 'CLOSED'
                AND c.updated_at > :previousPairingCutoff
            )
    """

    val PROFILE_COMPATIBILITY_FILTERS: String
        get() = """
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
            AND ${AGE_YEARS_SQL("pb.birth_date")} BETWEEN pa.preferred_min_age AND pa.preferred_max_age
            AND ${AGE_YEARS_SQL("pa.birth_date")} BETWEEN pb.preferred_min_age AND pb.preferred_max_age
    """

    val MUTUAL_DISTANCE_FILTER: String
        get() = """
            AND ABS(qb.latitude - qa.latitude) <= LEAST(pa.max_distance_km, pb.max_distance_km) / 110.574
            AND ${DISTANCE_KM_SQL} <= LEAST(pa.max_distance_km, pb.max_distance_km)
    """

    val ANCHOR_ORDER_LIMIT_AND_LOCK = """
        ORDER BY qa.entered_at, qa.id
        LIMIT 1
        FOR UPDATE OF qa SKIP LOCKED
    """

    val ANCHOR_ORDER_AND_LIMIT = """
        ORDER BY qa.entered_at, qa.id
        LIMIT 1
    """

    val PARTNER_ORDER_AND_LIMIT = """
        ORDER BY qb.entered_at, qb.id
        LIMIT :limit
    """

    val PARTNER_CLAIM_LOCK = """
        LIMIT 1
        FOR UPDATE OF qb SKIP LOCKED
    """

    val PARTNER_CLAIM_NO_LOCK = """
        LIMIT 1
    """

    val ACTIVE_MATCH_EXISTS: String
        get() = """
        EXISTS (
            SELECT 1
            FROM matches m
            WHERE ${UNORDERED_PARAM_PAIR_MATCH}
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

    val ACTIVE_CONNECTION_EXISTS: String
        get() = """
        EXISTS (
            SELECT 1
            FROM connections c
            WHERE ${UNORDERED_PARAM_PAIR_CONNECTION}
            AND c.state <> 'CLOSED'
        )
    """

    val HISTORICAL_MATCH_EXISTS: String
        get() = """
        EXISTS (
            SELECT 1
            FROM matches m
            WHERE ${UNORDERED_PARAM_PAIR_MATCH}
            AND (
                (
                    m.state = 'CHAT_REJECTED'
                    AND NOT EXISTS (
                        SELECT 1
                        FROM chats fc_mismatch
                        WHERE fc_mismatch.match_id = m.id
                            AND fc_mismatch.chat_type = 'FIRST_CHAT'
                            AND fc_mismatch.ended_reason = 'FIRST_CHAT_DECISION_MISMATCH'
                    )
                    AND m.updated_at > :previousPairingCutoff
                )
                OR (
                    m.state = 'CHAT_REJECTED'
                    AND EXISTS (
                        SELECT 1
                        FROM chats fc_mismatch
                        WHERE fc_mismatch.match_id = m.id
                            AND fc_mismatch.chat_type = 'FIRST_CHAT'
                            AND fc_mismatch.ended_reason = 'FIRST_CHAT_DECISION_MISMATCH'
                    )
                    AND COALESCE(
                        (
                            SELECT fc_mismatch.ended_at
                            FROM chats fc_mismatch
                            WHERE fc_mismatch.match_id = m.id
                                AND fc_mismatch.chat_type = 'FIRST_CHAT'
                                AND fc_mismatch.ended_reason = 'FIRST_CHAT_DECISION_MISMATCH'
                        ),
                        m.updated_at
                    ) > :firstChatDecisionMismatchCutoff
                )
                OR (
                    m.state = 'VISUAL_REJECTED'
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

    val HISTORICAL_CONNECTION_EXISTS: String
        get() = """
        EXISTS (
            SELECT 1
            FROM connections c
            WHERE ${UNORDERED_PARAM_PAIR_CONNECTION}
            AND c.state = 'CLOSED'
            AND c.updated_at > :previousPairingCutoff
        )
    """

    private val UNORDERED_QUEUE_PAIR_MATCH = """
        (
            (
                m.user_a_id = qa.user_id
                AND m.user_b_id = qb.user_id
            ) OR (
                m.user_a_id = qb.user_id
                AND m.user_b_id = qa.user_id
            )
        )
    """

    private val UNORDERED_QUEUE_PAIR_CONNECTION = """
        (
            (
                c.user_a_id = qa.user_id
                AND c.user_b_id = qb.user_id
            ) OR (
                c.user_a_id = qb.user_id
                AND c.user_b_id = qa.user_id
            )
        )
    """

    private val UNORDERED_PARAM_PAIR_MATCH = """
        (
            (
                m.user_a_id = :userAId
                AND m.user_b_id = :userBId
            ) OR (
                m.user_a_id = :userBId
                AND m.user_b_id = :userAId
            )
        )
    """

    private val UNORDERED_PARAM_PAIR_CONNECTION = """
        (
            (
                c.user_a_id = :userAId
                AND c.user_b_id = :userBId
            ) OR (
                c.user_a_id = :userBId
                AND c.user_b_id = :userAId
            )
        )
    """

    private val DISTANCE_KM_SQL = """
        6371.0 * 2.0 * ASIN(
            SQRT(
                LEAST(
                    1.0,
                    GREATEST(
                        0.0,
                        POWER(SIN(RADIANS(qb.latitude - qa.latitude) / 2.0), 2)
                        +
                        COS(RADIANS(qa.latitude))
                        * COS(RADIANS(qb.latitude))
                        * POWER(SIN(RADIANS(qb.longitude - qa.longitude) / 2.0), 2)
                    )
                )
            )
        )
    """

    private fun AGE_YEARS_SQL(birthDateExpression: String): String =
        """
        (
            EXTRACT(YEAR FROM CAST(:today AS DATE)) - EXTRACT(YEAR FROM $birthDateExpression) -
            CASE
                WHEN EXTRACT(MONTH FROM CAST(:today AS DATE)) < EXTRACT(MONTH FROM $birthDateExpression)
                    OR (
                        EXTRACT(MONTH FROM CAST(:today AS DATE)) = EXTRACT(MONTH FROM $birthDateExpression)
                        AND EXTRACT(DAY FROM CAST(:today AS DATE)) < EXTRACT(DAY FROM $birthDateExpression)
                    )
                THEN 1
                ELSE 0
            END
        )
        """.trimIndent()
}


