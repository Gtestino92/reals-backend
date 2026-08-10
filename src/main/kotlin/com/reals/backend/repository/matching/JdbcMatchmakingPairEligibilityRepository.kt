package com.reals.backend.repository.matching

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JdbcMatchmakingPairEligibilityRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) : MatchmakingPairEligibilityRepository {

    override fun findBlockingReason(
        userAId: UUID,
        userBId: UUID,
        exclusionPolicy: MatchmakingPairExclusionPolicy,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?,
        firstChatDecisionMismatchCutoff: OffsetDateTime?
    ): MatchmakingPairBlockingReason? {
        requireConsistentCutoffs(
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff,
            firstChatDecisionMismatchCutoff = firstChatDecisionMismatchCutoff
        )
        require(exclusionPolicy.excludeHistoricalPairings == (previousPairingCutoff != null)) {
            "Historical exclusion policy and cutoff parameters must match"
        }
        if (!exclusionPolicy.excludeActiveInteractions && !exclusionPolicy.excludeHistoricalPairings) {
            return null
        }

        val parameters =
            MapSqlParameterSource()
                .addValue("userAId", userAId)
                .addValue("userBId", userBId)

        if (exclusionPolicy.excludeHistoricalPairings) {
            parameters
                .addValue("previousPairingCutoff", previousPairingCutoff)
                .addValue("firstChatExpirationCutoff", firstChatExpirationCutoff)
                .addValue("firstChatDecisionMismatchCutoff", firstChatDecisionMismatchCutoff)
        }

        return jdbcTemplate.query(
            blockingReasonSql(exclusionPolicy),
            parameters
        ) { resultSet, _ ->
            MatchmakingPairBlockingReason.valueOf(resultSet.getString("reason"))
        }.firstOrNull()
    }

    private fun requireConsistentCutoffs(
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?,
        firstChatDecisionMismatchCutoff: OffsetDateTime?
    ) {
        require(
            (previousPairingCutoff == null) == (firstChatExpirationCutoff == null) &&
                (previousPairingCutoff == null) == (firstChatDecisionMismatchCutoff == null)
        ) {
            "Previous-pairing, first-chat expiration, and first-chat decision mismatch cutoffs must all be present or all be absent"
        }
    }

    private companion object {
        fun blockingReasonSql(exclusionPolicy: MatchmakingPairExclusionPolicy): String =
            when {
                exclusionPolicy.excludeActiveInteractions && exclusionPolicy.excludeHistoricalPairings ->
                    ACTIVE_PLUS_HISTORY_SQL

                exclusionPolicy.excludeActiveInteractions ->
                    ACTIVE_ONLY_SQL

                exclusionPolicy.excludeHistoricalPairings ->
                    HISTORY_ONLY_SQL

                else ->
                    NONE_SQL
            }

        val NONE_SQL =
            """
            SELECT reason
            FROM (
                SELECT 1 AS priority, 'ACTIVE_INTERACTION' AS reason
                WHERE false
            ) blockers
            ORDER BY priority
            LIMIT 1
            """.trimIndent()

        val ACTIVE_ONLY_SQL =
            """
            SELECT reason
            FROM (
                SELECT 1 AS priority, 'ACTIVE_INTERACTION' AS reason
                WHERE ${MatchmakingSqlFragments.ACTIVE_MATCH_EXISTS}
                    OR ${MatchmakingSqlFragments.ACTIVE_CONNECTION_EXISTS}
            ) blockers
            ORDER BY priority
            LIMIT 1
            """.trimIndent()

        val HISTORY_ONLY_SQL =
            """
            SELECT reason
            FROM (
                SELECT 2 AS priority, 'PREVIOUS_PAIRING_COOLDOWN' AS reason
                WHERE ${MatchmakingSqlFragments.HISTORICAL_MATCH_EXISTS}
                    OR ${MatchmakingSqlFragments.HISTORICAL_CONNECTION_EXISTS}
            ) blockers
            ORDER BY priority
            LIMIT 1
            """.trimIndent()

        val ACTIVE_PLUS_HISTORY_SQL =
            """
            SELECT reason
            FROM (
                SELECT 1 AS priority, 'ACTIVE_INTERACTION' AS reason
                WHERE ${MatchmakingSqlFragments.ACTIVE_MATCH_EXISTS}
                    OR ${MatchmakingSqlFragments.ACTIVE_CONNECTION_EXISTS}

                UNION ALL

                SELECT 2 AS priority, 'PREVIOUS_PAIRING_COOLDOWN' AS reason
                WHERE ${MatchmakingSqlFragments.HISTORICAL_MATCH_EXISTS}
                    OR ${MatchmakingSqlFragments.HISTORICAL_CONNECTION_EXISTS}
            ) blockers
            ORDER BY priority
            LIMIT 1
            """.trimIndent()
    }
}
