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
import javax.sql.DataSource

@Repository
class JdbcMatchmakingCandidateRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val dataSource: DataSource
) : MatchmakingCandidateRepository {

    private val isPostgres: Boolean by lazy {
        dataSource.connection.use { connection ->
            connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)
        }
    }

    override fun claimNextEligibleAnchorForUpdate(
        today: LocalDate,
        exclusionPolicy: MatchmakingPairExclusionPolicy,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?,
        firstChatDecisionMismatchCutoff: OffsetDateTime?
    ): MatchmakingAnchor? {
        requirePolicyMatchesCutoffs(
            exclusionPolicy = exclusionPolicy,
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff,
            firstChatDecisionMismatchCutoff = firstChatDecisionMismatchCutoff
        )
        val parameters = baseParameters(
            today = today,
            exclusionPolicy = exclusionPolicy,
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff,
            firstChatDecisionMismatchCutoff = firstChatDecisionMismatchCutoff
        )

        return jdbcTemplate.query(
            claimAnchorSql(exclusionPolicy),
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
        exclusionPolicy: MatchmakingPairExclusionPolicy,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?,
        firstChatDecisionMismatchCutoff: OffsetDateTime?
    ): List<MatchmakingPartnerCandidate> {
        requirePolicyMatchesCutoffs(
            exclusionPolicy = exclusionPolicy,
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff,
            firstChatDecisionMismatchCutoff = firstChatDecisionMismatchCutoff
        )
        val parameters = baseParameters(
            today = today,
            exclusionPolicy = exclusionPolicy,
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff,
            firstChatDecisionMismatchCutoff = firstChatDecisionMismatchCutoff
        )
            .addValue("anchorQueueEntryId", anchorQueueEntryId)
            .addValue("limit", limit)

        return jdbcTemplate.query(
            findPartnersSql(exclusionPolicy),
            parameters
        ) { resultSet, _ -> resultSet.toPartnerCandidate() }
    }

    override fun tryClaimEligiblePartnerForUpdate(
        anchorQueueEntryId: UUID,
        partnerQueueEntryId: UUID,
        today: LocalDate,
        exclusionPolicy: MatchmakingPairExclusionPolicy,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?,
        firstChatDecisionMismatchCutoff: OffsetDateTime?
    ): MatchmakingPartnerCandidate? {
        requirePolicyMatchesCutoffs(
            exclusionPolicy = exclusionPolicy,
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff,
            firstChatDecisionMismatchCutoff = firstChatDecisionMismatchCutoff
        )
        val parameters = baseParameters(
            today = today,
            exclusionPolicy = exclusionPolicy,
            previousPairingCutoff = previousPairingCutoff,
            firstChatExpirationCutoff = firstChatExpirationCutoff,
            firstChatDecisionMismatchCutoff = firstChatDecisionMismatchCutoff
        )
            .addValue("anchorQueueEntryId", anchorQueueEntryId)
            .addValue("partnerQueueEntryId", partnerQueueEntryId)

        return jdbcTemplate.query(
            claimPartnerSql(exclusionPolicy),
            parameters
        ) { resultSet, _ -> resultSet.toPartnerCandidate() }.firstOrNull()
    }

    private fun baseParameters(
        today: LocalDate,
        exclusionPolicy: MatchmakingPairExclusionPolicy,
        previousPairingCutoff: OffsetDateTime?,
        firstChatExpirationCutoff: OffsetDateTime?,
        firstChatDecisionMismatchCutoff: OffsetDateTime?
    ): MapSqlParameterSource {
        val parameters =
            MapSqlParameterSource()
                .addValue("today", today)

        if (exclusionPolicy.excludeHistoricalPairings) {
            parameters
                .addValue("previousPairingCutoff", previousPairingCutoff)
                .addValue("firstChatExpirationCutoff", firstChatExpirationCutoff)
                .addValue("firstChatDecisionMismatchCutoff", firstChatDecisionMismatchCutoff)
        }

        return parameters
    }

    private fun requirePolicyMatchesCutoffs(
        exclusionPolicy: MatchmakingPairExclusionPolicy,
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
        require(exclusionPolicy.excludeHistoricalPairings == (previousPairingCutoff != null)) {
            "Historical exclusion policy and cutoff parameters must match"
        }
    }

    private fun ResultSet.toPartnerCandidate(): MatchmakingPartnerCandidate =
        MatchmakingPartnerCandidate(
            partnerQueueEntryId = getObject("partner_queue_entry_id", UUID::class.java),
            partnerEnteredAt = getObject("partner_entered_at", OffsetDateTime::class.java),
            pair = MatchmakingCandidatePair(
                userAId = getObject("user_a_id", UUID::class.java),
                userBId = getObject("user_b_id", UUID::class.java),
                userALatitude = getDouble("user_a_latitude"),
                userALongitude = getDouble("user_a_longitude"),
                userBLatitude = getDouble("user_b_latitude"),
                userBLongitude = getDouble("user_b_longitude")
            )
        )

    private fun claimAnchorSql(exclusionPolicy: MatchmakingPairExclusionPolicy): String {
        val pairMatchExclusion = pairMatchExclusion(exclusionPolicy)
        val pairConnectionExclusion = pairConnectionExclusion(exclusionPolicy)
        val partnerProbe =
            if (isPostgres) {
                MatchmakingSqlFragments.partnerLateralJoin(pairMatchExclusion, pairConnectionExclusion)
            } else {
                MatchmakingSqlFragments.partnerExistsFilter(pairMatchExclusion, pairConnectionExclusion)
            }
        val orderingAndLock =
            if (isPostgres) {
                MatchmakingSqlFragments.ANCHOR_ORDER_LIMIT_AND_LOCK
            } else {
                MatchmakingSqlFragments.ANCHOR_ORDER_AND_LIMIT
            }

        return """
            ${MatchmakingSqlFragments.ANCHOR_SELECT_AND_BASE_JOINS}
            $partnerProbe
            ${MatchmakingSqlFragments.ANCHOR_BASE_FILTERS}
            $orderingAndLock
        """.trimIndent()
    }

    private fun findPartnersSql(exclusionPolicy: MatchmakingPairExclusionPolicy): String =
        """
        ${MatchmakingSqlFragments.PARTNER_SELECT_AND_BASE_JOINS}
        ${MatchmakingSqlFragments.PARTNER_DISCOVERY_BASE_FILTERS}
        ${MatchmakingSqlFragments.PARTNER_BASE_FILTERS}
        ${MatchmakingSqlFragments.PAIR_BLOCK_EXCLUSION}
        ${pairMatchExclusion(exclusionPolicy)}
        ${pairConnectionExclusion(exclusionPolicy)}
        ${MatchmakingSqlFragments.PROFILE_COMPATIBILITY_FILTERS}
        ${MatchmakingSqlFragments.MUTUAL_DISTANCE_FILTER}
        ${MatchmakingSqlFragments.PARTNER_ORDER_AND_LIMIT}
        """.trimIndent()

    private fun claimPartnerSql(exclusionPolicy: MatchmakingPairExclusionPolicy): String =
        """
        ${MatchmakingSqlFragments.PARTNER_SELECT_AND_BASE_JOINS}
        ${MatchmakingSqlFragments.PARTNER_CLAIM_BASE_FILTERS}
        ${MatchmakingSqlFragments.PARTNER_BASE_FILTERS}
        ${MatchmakingSqlFragments.PAIR_BLOCK_EXCLUSION}
        ${pairMatchExclusion(exclusionPolicy)}
        ${pairConnectionExclusion(exclusionPolicy)}
        ${MatchmakingSqlFragments.PROFILE_COMPATIBILITY_FILTERS}
        ${MatchmakingSqlFragments.MUTUAL_DISTANCE_FILTER}
        ${if (isPostgres) MatchmakingSqlFragments.PARTNER_CLAIM_LOCK else MatchmakingSqlFragments.PARTNER_CLAIM_NO_LOCK}
        """.trimIndent()

    private fun pairMatchExclusion(exclusionPolicy: MatchmakingPairExclusionPolicy): String =
        when {
            exclusionPolicy.excludeActiveInteractions && exclusionPolicy.excludeHistoricalPairings ->
                MatchmakingSqlFragments.PAIR_ACTIVE_OR_HISTORICAL_MATCH_EXCLUSION

            exclusionPolicy.excludeActiveInteractions ->
                MatchmakingSqlFragments.PAIR_ACTIVE_MATCH_EXCLUSION

            exclusionPolicy.excludeHistoricalPairings ->
                MatchmakingSqlFragments.PAIR_HISTORICAL_MATCH_EXCLUSION

            else ->
                ""
        }

    private fun pairConnectionExclusion(exclusionPolicy: MatchmakingPairExclusionPolicy): String =
        when {
            exclusionPolicy.excludeActiveInteractions && exclusionPolicy.excludeHistoricalPairings ->
                MatchmakingSqlFragments.PAIR_ACTIVE_OR_HISTORICAL_CONNECTION_EXCLUSION

            exclusionPolicy.excludeActiveInteractions ->
                MatchmakingSqlFragments.PAIR_ACTIVE_CONNECTION_EXCLUSION

            exclusionPolicy.excludeHistoricalPairings ->
                MatchmakingSqlFragments.PAIR_HISTORICAL_CONNECTION_EXCLUSION

            else ->
                ""
        }
}
