package com.reals.backend.service.localdev

import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class LocalDevPairHistoryResetService(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    @Transactional
    fun resetPairHistory(
        userIdA: UUID,
        userIdB: UUID
    ): LocalDevPairHistoryResetResult {
        require(userIdA != userIdB) { "userIdA and userIdB must be different users" }

        val orderedUserIds = listOf(userIdA, userIdB).sorted()
        val userParams = params("userIds", orderedUserIds)
        val lockedUserIds = queryUuidList(
            """
            SELECT id
            FROM users
            WHERE id IN (:userIds)
            ORDER BY id
            FOR UPDATE
            """,
            userParams
        )
        if (lockedUserIds.size != 2) {
            throw DomainNotFoundException(
                code = DomainErrorCode.USER_NOT_FOUND,
                message = "Both users must exist before resetting pair history"
            )
        }

        jdbcTemplate.queryForList(
            """
            SELECT id
            FROM matchmaking_queue
            WHERE user_id IN (:userIds)
            ORDER BY user_id
            FOR UPDATE
            """,
            userParams
        )

        val pairParams = MapSqlParameterSource()
            .addValue("userIdA", userIdA)
            .addValue("userIdB", userIdB)

        val matchIds = queryUuidList(
            """
            SELECT id
            FROM matches
            WHERE (user_a_id = :userIdA AND user_b_id = :userIdB)
               OR (user_a_id = :userIdB AND user_b_id = :userIdA)
            """,
            pairParams
        )
        val connectionIds = queryUuidList(
            """
            SELECT id
            FROM connections
            WHERE ${inOrFalse("match_id", matchIds, "matchIds")}
               OR ((user_a_id = :userIdA AND user_b_id = :userIdB)
               OR (user_a_id = :userIdB AND user_b_id = :userIdA))
            """,
            pairParams.addOptionalUuids("matchIds", matchIds)
        )
        val chatIds = queryUuidList(
            """
            SELECT id
            FROM chats
            WHERE ${inOrFalse("connection_id", connectionIds, "connectionIds")}
               OR ${inOrFalse("match_id", matchIds, "matchIds")}
            """,
            MapSqlParameterSource()
                .addOptionalUuids("connectionIds", connectionIds)
                .addOptionalUuids("matchIds", matchIds)
        )
        val affectedAggregateIds = (matchIds + connectionIds + chatIds).distinct()

        if (affectedAggregateIds.isNotEmpty()) {
            update(
                "DELETE FROM push_notification_deliveries WHERE aggregate_id IN (:aggregateIds)",
                params("aggregateIds", affectedAggregateIds)
            )
        }

        // DEV-only reset: new pair-history persistence must be reviewed here to preserve FK-safe cleanup.
        val reliabilityEventsDeleted = updateWhenAny(
            """
            DELETE FROM user_reliability_events
            WHERE ${inOrFalse("related_match_id", matchIds, "matchIds")}
               OR ${inOrFalse("related_connection_id", connectionIds, "connectionIds")}
               OR ${inOrFalse("related_chat_id", chatIds, "chatIds")}
            """,
            MapSqlParameterSource()
                .addOptionalUuids("matchIds", matchIds)
                .addOptionalUuids("connectionIds", connectionIds)
                .addOptionalUuids("chatIds", chatIds),
            matchIds,
            connectionIds,
            chatIds
        )

        updateWhenAny(
            """
            DELETE FROM active_engagement_locks
            WHERE (engagement_type = 'MATCH' AND ${inOrFalse("engagement_id", matchIds, "matchIds")})
               OR (engagement_type = 'CONNECTION' AND ${inOrFalse("engagement_id", connectionIds, "connectionIds")})
            """,
            MapSqlParameterSource()
                .addOptionalUuids("matchIds", matchIds)
                .addOptionalUuids("connectionIds", connectionIds),
            matchIds,
            connectionIds
        )

        updateWhenAny(
            "UPDATE safety_reports SET chat_id = NULL WHERE chat_id IN (:chatIds)",
            params("chatIds", chatIds),
            chatIds
        )

        updateWhenAny(
            """
            DELETE FROM second_chat_resolution_requests
            WHERE reference_message_id IN (
                SELECT id FROM chat_messages WHERE chat_session_id IN (:chatIds)
            )
            """,
            params("chatIds", chatIds),
            chatIds
        )
        updateWhenAny(
            """
            DELETE FROM second_chat_resolution_requests
            WHERE ${inOrFalse("chat_id", chatIds, "chatIds")}
               OR ${inOrFalse("connection_id", connectionIds, "connectionIds")}
            """,
            MapSqlParameterSource()
                .addOptionalUuids("chatIds", chatIds)
                .addOptionalUuids("connectionIds", connectionIds),
            chatIds,
            connectionIds
        )
        updateWhenAny("DELETE FROM first_chat_guidance WHERE chat_id IN (:chatIds)", params("chatIds", chatIds), chatIds)
        updateWhenAny(
            "DELETE FROM conversation_prompt_snapshots WHERE chat_id IN (:chatIds)",
            params("chatIds", chatIds),
            chatIds
        )
        updateWhenAny("DELETE FROM chat_exit_requests WHERE chat_id IN (:chatIds)", params("chatIds", chatIds), chatIds)
        updateWhenAny(
            """
            DELETE FROM chat_decisions
            WHERE ${inOrFalse("chat_id", chatIds, "chatIds")}
               OR ${inOrFalse("match_id", matchIds, "matchIds")}
            """,
            MapSqlParameterSource()
                .addOptionalUuids("chatIds", chatIds)
                .addOptionalUuids("matchIds", matchIds),
            chatIds,
            matchIds
        )
        updateWhenAny("DELETE FROM chat_messages WHERE chat_session_id IN (:chatIds)", params("chatIds", chatIds), chatIds)
        val chatsDeleted = updateWhenAny(
            "DELETE FROM chats WHERE id IN (:chatIds)",
            params("chatIds", chatIds),
            chatIds
        )

        updateWhenAny(
            "DELETE FROM connection_home_dismissals WHERE connection_id IN (:connectionIds)",
            params("connectionIds", connectionIds),
            connectionIds
        )
        updateWhenAny(
            "DELETE FROM second_chat_participations WHERE connection_id IN (:connectionIds)",
            params("connectionIds", connectionIds),
            connectionIds
        )
        updateWhenAny("DELETE FROM schedule_proposals WHERE connection_id IN (:connectionIds)", params("connectionIds", connectionIds), connectionIds)
        updateWhenAny("DELETE FROM schedule_negotiations WHERE connection_id IN (:connectionIds)", params("connectionIds", connectionIds), connectionIds)
        val connectionsDeleted = updateWhenAny(
            "DELETE FROM connections WHERE id IN (:connectionIds)",
            params("connectionIds", connectionIds),
            connectionIds
        )

        updateWhenAny(
            "DELETE FROM visual_review_affinity_indicators WHERE match_id IN (:matchIds)",
            params("matchIds", matchIds),
            matchIds
        )
        updateWhenAny("DELETE FROM visual_reviews WHERE match_id IN (:matchIds)", params("matchIds", matchIds), matchIds)
        val matchesDeleted = updateWhenAny(
            "DELETE FROM matches WHERE id IN (:matchIds)",
            params("matchIds", matchIds),
            matchIds
        )

        update("DELETE FROM user_home_status WHERE user_id IN (:userIds)", userParams)

        return LocalDevPairHistoryResetResult(
            matchesDeleted = matchesDeleted,
            connectionsDeleted = connectionsDeleted,
            chatsDeleted = chatsDeleted,
            reliabilityEventsDeleted = reliabilityEventsDeleted
        )
    }

    private fun queryUuidList(
        sql: String,
        params: MapSqlParameterSource
    ): List<UUID> =
        jdbcTemplate.query(sql.trimIndent(), params) { rs, _ -> rs.getObject("id", UUID::class.java) }

    private fun updateWhenAny(
        sql: String,
        params: MapSqlParameterSource,
        vararg idLists: List<UUID>
    ): Int =
        if (idLists.any { it.isNotEmpty() }) update(sql, params) else 0

    private fun update(
        sql: String,
        params: MapSqlParameterSource
    ): Int =
        jdbcTemplate.update(sql.trimIndent(), params)

    private fun inOrFalse(
        column: String,
        values: List<UUID>,
        paramName: String
    ): String =
        if (values.isEmpty()) "FALSE" else "$column IN (:$paramName)"

    private fun params(
        name: String,
        values: List<UUID>
    ): MapSqlParameterSource =
        MapSqlParameterSource().addValue(name, values)

    private fun MapSqlParameterSource.addOptionalUuids(
        name: String,
        values: List<UUID>
    ): MapSqlParameterSource =
        if (values.isEmpty()) this else addValue(name, values)
}

data class LocalDevPairHistoryResetResult(
    val matchesDeleted: Int,
    val connectionsDeleted: Int,
    val chatsDeleted: Int,
    val reliabilityEventsDeleted: Int
)
