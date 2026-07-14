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
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?
    ): MatchmakingPairBlockingReason? {
        val includeHistoricalExclusion = requireConsistentCutoffs(
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff
        )

        val parameters =
            MapSqlParameterSource()
                .addValue("userAId", userAId)
                .addValue("userBId", userBId)

        if (includeHistoricalExclusion) {
            parameters
                .addValue("previousPairingCutoff", previousPairingCutoff)
                .addValue("firstChatExpirationCutoff", firstChatExpirationCutoff)
        }

        return jdbcTemplate.query(
            if (includeHistoricalExclusion) ACTIVE_PLUS_HISTORY_SQL else ACTIVE_ONLY_SQL,
            parameters
        ) { resultSet, _ ->
            MatchmakingPairBlockingReason.valueOf(resultSet.getString("reason"))
        }.firstOrNull()
    }

    private fun requireConsistentCutoffs(
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?
    ): Boolean {
        require((previousPairingCutoff == null) == (firstChatExpirationCutoff == null)) {
            "Previous-pairing and first-chat expiration cutoffs must both be present or both be absent"
        }
        return previousPairingCutoff != null
    }

    private companion object {
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
