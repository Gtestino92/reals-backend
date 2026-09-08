package com.reals.backend.integration.service

import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.integration.BaseIT
import com.reals.backend.repository.matching.MatchmakingPairBlockingReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime
import java.util.UUID

@TestPropertySource(
    properties = [
        "matchmaking.allow-active-pair-duplicates=true",
        "matchmaking.exclude-previous-pairing=true",
        "engagement.max-active-matches=100",
        "engagement.max-active-connections=100",
        "engagement.reliability-capacity.match.min=100",
        "engagement.reliability-capacity.match.max=100",
        "engagement.reliability-capacity.connection.min=100",
        "engagement.reliability-capacity.connection.max=100"
    ]
)
class MatchmakingActiveDuplicatesWithHistoryIntegrationTest : BaseIT() {

    @Test
    fun `active duplicate mode can still enforce historical cooldown independently`() {
        val now = fixedNow()
        val activeMatch = createQueuedCompatiblePair("active-with-history-match")
        saveMatch(activeMatch.first, activeMatch.second, MatchState.CHAT_ACTIVE, now)
        val activeConnection = createQueuedCompatiblePair("active-with-history-connection")
        saveConnection(activeConnection.first, activeConnection.second, ConnectionState.SECOND_CHAT, now)
        val recentTerminal = createQueuedCompatiblePair("active-with-history-terminal")
        saveMatch(recentTerminal.first, recentTerminal.second, MatchState.CHAT_REJECTED, now.minusDays(1))
        val recentClosedConnection = createQueuedCompatiblePair("active-with-history-closed")
        saveConnection(recentClosedConnection.first, recentClosedConnection.second, ConnectionState.CLOSED, now.minusDays(1))
        val expiredTerminal = createQueuedCompatiblePair("active-with-history-expired")
        saveMatch(expiredTerminal.first, expiredTerminal.second, MatchState.VISUAL_REJECTED, now.minusDays(30))

        assertTrue(matchmakingPairEligibilityService.hasActiveInteraction(activeMatch.first, activeMatch.second))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(activeMatch.first, activeMatch.second, now))
        assertTrue(queryContainsPair(activeMatch.first, activeMatch.second, now))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(activeConnection.first, activeConnection.second, now))
        assertTrue(queryContainsPair(activeConnection.first, activeConnection.second, now))
        assertFalse(matchmakingPairEligibilityService.isPairEligible(recentTerminal.first, recentTerminal.second, now))
        assertFalse(queryContainsPair(recentTerminal.first, recentTerminal.second, now))
        assertEquals(
            MatchmakingPairBlockingReason.PREVIOUS_PAIRING_COOLDOWN,
            blockingReason(recentTerminal.first, recentTerminal.second, now)
        )
        assertFalse(matchmakingPairEligibilityService.isPairEligible(recentClosedConnection.first, recentClosedConnection.second, now))
        assertFalse(queryContainsPair(recentClosedConnection.first, recentClosedConnection.second, now))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(expiredTerminal.first, expiredTerminal.second, now))
        assertTrue(queryContainsPair(expiredTerminal.first, expiredTerminal.second, now))
    }

    private fun createQueuedCompatiblePair(prefix: String): Pair<UUID, UUID> {
        val userA = createActiveProfile(
            email = "$prefix-a-${UUID.randomUUID()}@example.com",
            displayName = "$prefix A",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )
        val userB = createActiveProfile(
            email = "$prefix-b-${UUID.randomUUID()}@example.com",
            displayName = "$prefix B",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
        enqueueForMatchmaking(userA)
        enqueueForMatchmaking(userB)
        return Pair(userA, userB)
    }

    private fun fixedNow(): OffsetDateTime =
        OffsetDateTime.parse("2026-07-14T12:00:00Z")

    private fun queryContainsPair(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime
    ): Boolean {
        val policy = matchmakingPairEligibilityService.effectiveExclusionPolicy()
        val previousPairingCutoff = matchmakingPairEligibilityService.previousPairingCutoff(now)
        val firstChatExpirationCutoff = matchmakingPairEligibilityService.firstChatExpirationCutoff(now)
        val firstChatDecisionMismatchCutoff = matchmakingPairEligibilityService.firstChatDecisionMismatchCutoff(now)
        val userAQueueEntry = matchmakingQueueRepository.findByUserId(userAId)
        val userBQueueEntry = matchmakingQueueRepository.findByUserId(userBId)

        return listOfNotNull(userAQueueEntry, userBQueueEntry).any { anchor ->
            matchmakingCandidateRepository.findEligiblePartnerCandidates(
                anchorQueueEntryId = anchor.id,
                limit = 100,
                today = now.toLocalDate(),
                exclusionPolicy = policy,
                previousPairingCutoff = previousPairingCutoff,
                firstChatExpirationCutoff = firstChatExpirationCutoff,
                firstChatDecisionMismatchCutoff = firstChatDecisionMismatchCutoff
            ).any {
                (it.pair.userAId == userAId && it.pair.userBId == userBId) ||
                    (it.pair.userAId == userBId && it.pair.userBId == userAId)
            }
        }
    }

    private fun blockingReason(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime
    ): MatchmakingPairBlockingReason? =
        matchmakingPairEligibilityRepository.findBlockingReason(
            userAId = userAId,
            userBId = userBId,
            exclusionPolicy = matchmakingPairEligibilityService.effectiveExclusionPolicy(),
            previousPairingCutoff = matchmakingPairEligibilityService.previousPairingCutoff(now),
            firstChatExpirationCutoff = matchmakingPairEligibilityService.firstChatExpirationCutoff(now),
            firstChatDecisionMismatchCutoff = matchmakingPairEligibilityService.firstChatDecisionMismatchCutoff(now)
        )

    private fun saveMatch(
        userAId: UUID,
        userBId: UUID,
        state: MatchState,
        updatedAt: OffsetDateTime
    ): Match =
        matchRepository.saveAndFlush(
            Match(
                userAId = userAId,
                userBId = userBId,
                state = state,
                createdAt = updatedAt.minusMinutes(1),
                updatedAt = updatedAt
            )
        )

    private fun saveConnection(
        userAId: UUID,
        userBId: UUID,
        state: ConnectionState,
        updatedAt: OffsetDateTime
    ): Connection {
        val match = saveMatch(userAId, userBId, MatchState.VISUAL_APPROVED, updatedAt.minusDays(60))
        return connectionRepository.saveAndFlush(
            Connection(
                matchId = match.id,
                userAId = userAId,
                userBId = userBId,
                state = state,
                schedulingExpiresAt = updatedAt.plusDays(1),
                createdAt = updatedAt.minusMinutes(1),
                updatedAt = updatedAt
            )
        )
    }
}
