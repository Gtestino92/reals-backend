package com.reals.backend.repository.matching

import com.reals.backend.domain.MatchmakingCandidatePair
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JdbcMatchmakingCandidateRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) : MatchmakingCandidateRepository {

    override fun findEligibleCandidatePairsForUpdate(
        limit: Int,
        today: LocalDate,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?
    ): List<MatchmakingCandidatePair> {
        val includeHistoricalExclusion =
            previousPairingCutoff != null && firstChatExpirationCutoff != null

        val parameters =
            MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("today", today)

        if (includeHistoricalExclusion) {
            parameters
                .addValue("previousPairingCutoff", previousPairingCutoff)
                .addValue("firstChatExpirationCutoff", firstChatExpirationCutoff)
        }

        return jdbcTemplate.query(
            if (includeHistoricalExclusion) ACTIVE_PLUS_HISTORY_SQL else ACTIVE_ONLY_SQL,
            parameters
        ) { resultSet, _ -> resultSet.toCandidatePair() }
    }

    private fun ResultSet.toCandidatePair(): MatchmakingCandidatePair =
        MatchmakingCandidatePair(
            userAId = getObject("user_a_id", UUID::class.java),
            userBId = getObject("user_b_id", UUID::class.java),
            userALatitude = getDouble("user_a_latitude"),
            userALongitude = getDouble("user_a_longitude"),
            userBLatitude = getDouble("user_b_latitude"),
            userBLongitude = getDouble("user_b_longitude")
        )

    private companion object {
        val ACTIVE_ONLY_SQL =
            """
            ${MatchmakingSqlFragments.SELECT_CANDIDATE_PAIRS_AND_BASE_JOINS}
            ${MatchmakingSqlFragments.BASE_COMPATIBILITY_FILTERS}
            ${MatchmakingSqlFragments.QUEUE_BLOCK_EXCLUSION}
            ${MatchmakingSqlFragments.QUEUE_ACTIVE_MATCH_EXCLUSION}
            ${MatchmakingSqlFragments.QUEUE_ACTIVE_CONNECTION_EXCLUSION}
            ${MatchmakingSqlFragments.PROFILE_COMPATIBILITY_FILTERS}
            ${MatchmakingSqlFragments.ORDER_LIMIT_AND_LOCK}
            """.trimIndent()

        val ACTIVE_PLUS_HISTORY_SQL =
            """
            ${MatchmakingSqlFragments.SELECT_CANDIDATE_PAIRS_AND_BASE_JOINS}
            ${MatchmakingSqlFragments.BASE_COMPATIBILITY_FILTERS}
            ${MatchmakingSqlFragments.QUEUE_BLOCK_EXCLUSION}
            ${MatchmakingSqlFragments.QUEUE_ACTIVE_OR_HISTORICAL_MATCH_EXCLUSION}
            ${MatchmakingSqlFragments.QUEUE_ACTIVE_OR_HISTORICAL_CONNECTION_EXCLUSION}
            ${MatchmakingSqlFragments.PROFILE_COMPATIBILITY_FILTERS}
            ${MatchmakingSqlFragments.ORDER_LIMIT_AND_LOCK}
            """.trimIndent()
    }
}
