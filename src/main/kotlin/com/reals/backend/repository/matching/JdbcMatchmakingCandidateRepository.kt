package com.reals.backend.repository.matching

import com.reals.backend.domain.MatchmakingAnchor
import com.reals.backend.domain.MatchmakingCandidatePair
import com.reals.backend.domain.MatchmakingPartnerCandidate
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

    override fun claimNextEligibleAnchorForUpdate(
        today: LocalDate,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?
    ): MatchmakingAnchor? {
        val includeHistoricalExclusion = requireConsistentCutoffs(
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff
        )
        val parameters = baseParameters(
            today = today,
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff,
            includeHistoricalExclusion = includeHistoricalExclusion
        )

        return jdbcTemplate.query(
            if (includeHistoricalExclusion) CLAIM_ANCHOR_WITH_HISTORY_SQL else CLAIM_ANCHOR_ACTIVE_ONLY_SQL,
            parameters
        ) { resultSet, _ ->
            MatchmakingAnchor(
                queueEntryId = resultSet.getObject("anchor_queue_entry_id", UUID::class.java),
                userId = resultSet.getObject("anchor_user_id", UUID::class.java)
            )
        }.firstOrNull()
    }

    override fun findEligiblePartnerCandidates(
        anchorQueueEntryId: UUID,
        limit: Int,
        today: LocalDate,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?
    ): List<MatchmakingPartnerCandidate> {
        val includeHistoricalExclusion = requireConsistentCutoffs(
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff
        )
        val parameters = baseParameters(
            today = today,
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff,
            includeHistoricalExclusion = includeHistoricalExclusion
        )
            .addValue("anchorQueueEntryId", anchorQueueEntryId)
            .addValue("limit", limit)

        return jdbcTemplate.query(
            if (includeHistoricalExclusion) FIND_PARTNERS_WITH_HISTORY_SQL else FIND_PARTNERS_ACTIVE_ONLY_SQL,
            parameters
        ) { resultSet, _ -> resultSet.toPartnerCandidate() }
    }

    override fun tryClaimEligiblePartnerForUpdate(
        anchorQueueEntryId: UUID,
        partnerQueueEntryId: UUID,
        today: LocalDate,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?
    ): MatchmakingPartnerCandidate? {
        val includeHistoricalExclusion = requireConsistentCutoffs(
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff
        )
        val parameters = baseParameters(
            today = today,
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff,
            includeHistoricalExclusion = includeHistoricalExclusion
        )
            .addValue("anchorQueueEntryId", anchorQueueEntryId)
            .addValue("partnerQueueEntryId", partnerQueueEntryId)

        return jdbcTemplate.query(
            if (includeHistoricalExclusion) CLAIM_PARTNER_WITH_HISTORY_SQL else CLAIM_PARTNER_ACTIVE_ONLY_SQL,
            parameters
        ) { resultSet, _ -> resultSet.toPartnerCandidate() }.firstOrNull()
    }

    private fun baseParameters(
        today: LocalDate,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?,
        includeHistoricalExclusion: Boolean
    ): MapSqlParameterSource {
        val parameters =
            MapSqlParameterSource()
                .addValue("today", today)

        if (includeHistoricalExclusion) {
            parameters
                .addValue("previousPairingCutoff", previousPairingCutoff)
                .addValue("firstChatExpirationCutoff", firstChatExpirationCutoff)
        }

        return parameters
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

    private fun ResultSet.toPartnerCandidate(): MatchmakingPartnerCandidate =
        MatchmakingPartnerCandidate(
            partnerQueueEntryId = getObject("partner_queue_entry_id", UUID::class.java),
            pair = MatchmakingCandidatePair(
                userAId = getObject("user_a_id", UUID::class.java),
                userBId = getObject("user_b_id", UUID::class.java),
                userALatitude = getDouble("user_a_latitude"),
                userALongitude = getDouble("user_a_longitude"),
                userBLatitude = getDouble("user_b_latitude"),
                userBLongitude = getDouble("user_b_longitude")
            )
        )

    private companion object {
        val CLAIM_ANCHOR_ACTIVE_ONLY_SQL =
            """
            ${MatchmakingSqlFragments.ANCHOR_SELECT_AND_BASE_JOINS}
            ${MatchmakingSqlFragments.PARTNER_LATERAL_JOIN}
            ${MatchmakingSqlFragments.ANCHOR_BASE_FILTERS}
            ${MatchmakingSqlFragments.ANCHOR_ORDER_LIMIT_AND_LOCK}
            """.trimIndent()

        val CLAIM_ANCHOR_WITH_HISTORY_SQL =
            """
            ${MatchmakingSqlFragments.ANCHOR_SELECT_AND_BASE_JOINS}
            ${MatchmakingSqlFragments.PARTNER_LATERAL_JOIN_WITH_HISTORY}
            ${MatchmakingSqlFragments.ANCHOR_BASE_FILTERS}
            ${MatchmakingSqlFragments.ANCHOR_ORDER_LIMIT_AND_LOCK}
            """.trimIndent()

        val FIND_PARTNERS_ACTIVE_ONLY_SQL =
            """
            ${MatchmakingSqlFragments.PARTNER_SELECT_AND_BASE_JOINS}
            ${MatchmakingSqlFragments.PARTNER_DISCOVERY_BASE_FILTERS}
            ${MatchmakingSqlFragments.PARTNER_BASE_FILTERS}
            ${MatchmakingSqlFragments.PAIR_BLOCK_EXCLUSION}
            ${MatchmakingSqlFragments.PAIR_ACTIVE_MATCH_EXCLUSION}
            ${MatchmakingSqlFragments.PAIR_ACTIVE_CONNECTION_EXCLUSION}
            ${MatchmakingSqlFragments.PROFILE_COMPATIBILITY_FILTERS}
            ${MatchmakingSqlFragments.MUTUAL_DISTANCE_FILTER}
            ${MatchmakingSqlFragments.PARTNER_ORDER_AND_LIMIT}
            """.trimIndent()

        val FIND_PARTNERS_WITH_HISTORY_SQL =
            """
            ${MatchmakingSqlFragments.PARTNER_SELECT_AND_BASE_JOINS}
            ${MatchmakingSqlFragments.PARTNER_DISCOVERY_BASE_FILTERS}
            ${MatchmakingSqlFragments.PARTNER_BASE_FILTERS}
            ${MatchmakingSqlFragments.PAIR_BLOCK_EXCLUSION}
            ${MatchmakingSqlFragments.PAIR_ACTIVE_OR_HISTORICAL_MATCH_EXCLUSION}
            ${MatchmakingSqlFragments.PAIR_ACTIVE_OR_HISTORICAL_CONNECTION_EXCLUSION}
            ${MatchmakingSqlFragments.PROFILE_COMPATIBILITY_FILTERS}
            ${MatchmakingSqlFragments.MUTUAL_DISTANCE_FILTER}
            ${MatchmakingSqlFragments.PARTNER_ORDER_AND_LIMIT}
            """.trimIndent()

        val CLAIM_PARTNER_ACTIVE_ONLY_SQL =
            """
            ${MatchmakingSqlFragments.PARTNER_SELECT_AND_BASE_JOINS}
            ${MatchmakingSqlFragments.PARTNER_CLAIM_BASE_FILTERS}
            ${MatchmakingSqlFragments.PARTNER_BASE_FILTERS}
            ${MatchmakingSqlFragments.PAIR_BLOCK_EXCLUSION}
            ${MatchmakingSqlFragments.PAIR_ACTIVE_MATCH_EXCLUSION}
            ${MatchmakingSqlFragments.PAIR_ACTIVE_CONNECTION_EXCLUSION}
            ${MatchmakingSqlFragments.PROFILE_COMPATIBILITY_FILTERS}
            ${MatchmakingSqlFragments.MUTUAL_DISTANCE_FILTER}
            ${MatchmakingSqlFragments.PARTNER_CLAIM_LOCK}
            """.trimIndent()

        val CLAIM_PARTNER_WITH_HISTORY_SQL =
            """
            ${MatchmakingSqlFragments.PARTNER_SELECT_AND_BASE_JOINS}
            ${MatchmakingSqlFragments.PARTNER_CLAIM_BASE_FILTERS}
            ${MatchmakingSqlFragments.PARTNER_BASE_FILTERS}
            ${MatchmakingSqlFragments.PAIR_BLOCK_EXCLUSION}
            ${MatchmakingSqlFragments.PAIR_ACTIVE_OR_HISTORICAL_MATCH_EXCLUSION}
            ${MatchmakingSqlFragments.PAIR_ACTIVE_OR_HISTORICAL_CONNECTION_EXCLUSION}
            ${MatchmakingSqlFragments.PROFILE_COMPATIBILITY_FILTERS}
            ${MatchmakingSqlFragments.MUTUAL_DISTANCE_FILTER}
            ${MatchmakingSqlFragments.PARTNER_CLAIM_LOCK}
            """.trimIndent()
    }
}
