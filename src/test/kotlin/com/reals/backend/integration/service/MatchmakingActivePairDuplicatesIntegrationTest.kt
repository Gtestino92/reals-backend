package com.reals.backend.integration.service

import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime
import java.util.UUID

@TestPropertySource(
    properties = [
        "matchmaking.allow-active-pair-duplicates=true",
        "matchmaking.exclude-previous-pairing=false",
        "engagement.max-active-matches=100",
        "engagement.max-active-connections=100",
        "engagement.reliability-capacity.match.min=100",
        "engagement.reliability-capacity.match.max=100",
        "engagement.reliability-capacity.connection.min=100",
        "engagement.reliability-capacity.connection.max=100"
    ]
)
class MatchmakingActivePairDuplicatesIntegrationTest : BaseIT() {

    @Test
    fun `active duplicate mode allows active pair through all PR2 phases`() {
        val now = fixedNow()
        val (userA, userB) = createQueuedCompatiblePair("active-duplicate-full")
        saveMatch(userA, userB, MatchState.CHAT_ACTIVE, now)

        assertTrue(matchmakingPairEligibilityService.hasActiveInteraction(userA, userB))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(userA, userB, now))
        assertTrue(queryContainsPair(userA, userB, now))
        assertNotNull(claimPair(userA, userB, now))

        matchService.createMatch(userA, userB)

        assertEquals(2, countMatchesBetween(userA, userB))
    }

    @Test
    fun `active duplicate mode ignores approved transition and active connection but keeps block exclusion`() {
        val now = fixedNow()
        val approvedWithoutConnection = createQueuedCompatiblePair("active-duplicate-approved")
        saveMatch(approvedWithoutConnection.first, approvedWithoutConnection.second, MatchState.VISUAL_APPROVED, now)
        val activeConnection = createQueuedCompatiblePair("active-duplicate-connection")
        saveConnection(activeConnection.first, activeConnection.second, ConnectionState.SCHEDULING_PHASE, now)
        val blocked = createQueuedCompatiblePair("active-duplicate-blocked")
        userBlockService.blockUser(
            blockerUserId = blocked.first,
            blockedUserId = blocked.second,
            source = UserBlockSource.MANUAL
        )

        assertTrue(matchmakingPairEligibilityService.isPairEligible(approvedWithoutConnection.first, approvedWithoutConnection.second, now))
        assertTrue(queryContainsPair(approvedWithoutConnection.first, approvedWithoutConnection.second, now))
        assertNotNull(claimPair(approvedWithoutConnection.first, approvedWithoutConnection.second, now))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(activeConnection.first, activeConnection.second, now))
        assertTrue(queryContainsPair(activeConnection.first, activeConnection.second, now))
        assertFalse(matchmakingPairEligibilityService.isPairEligible(blocked.first, blocked.second, now))
        assertFalse(queryContainsPair(blocked.first, blocked.second, now))
        assertThrows<RuntimeException> {
            matchService.createMatch(blocked.first, blocked.second)
        }
    }

    @Test
    fun `terminal history remains eligible when both local duplicate and history exclusions are disabled`() {
        val now = fixedNow()
        val terminalMatch = createQueuedCompatiblePair("active-duplicate-terminal-match")
        saveMatch(terminalMatch.first, terminalMatch.second, MatchState.CHAT_REJECTED, now.minusDays(1))
        val closedConnection = createQueuedCompatiblePair("active-duplicate-closed-connection")
        saveConnection(closedConnection.first, closedConnection.second, ConnectionState.CLOSED, now.minusDays(1))

        assertTrue(matchmakingPairEligibilityService.isPairEligible(terminalMatch.first, terminalMatch.second, now))
        assertTrue(queryContainsPair(terminalMatch.first, terminalMatch.second, now))
        assertTrue(matchmakingPairEligibilityService.isPairEligible(closedConnection.first, closedConnection.second, now))
        assertTrue(queryContainsPair(closedConnection.first, closedConnection.second, now))
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
        val userAQueueEntry = matchmakingQueueRepository.findByUserId(userAId)
        val userBQueueEntry = matchmakingQueueRepository.findByUserId(userBId)

        return listOfNotNull(userAQueueEntry, userBQueueEntry).any { anchor ->
            matchmakingCandidateRepository.findEligiblePartnerCandidates(
                anchorQueueEntryId = anchor.id,
                limit = 100,
                today = now.toLocalDate(),
                exclusionPolicy = policy,
                previousPairingCutoff = null,
                firstChatExpirationCutoff = null,
                firstChatDecisionMismatchCutoff = null
            ).any {
                (it.pair.userAId == userAId && it.pair.userBId == userBId) ||
                    (it.pair.userAId == userBId && it.pair.userBId == userAId)
            }
        }
    }

    private fun claimPair(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime
    ) =
        matchmakingCandidateRepository.tryClaimEligiblePartnerForUpdate(
            anchorQueueEntryId = matchmakingQueueRepository.findByUserId(userAId)?.id
                ?: error("Expected anchor queue entry"),
            partnerQueueEntryId = matchmakingQueueRepository.findByUserId(userBId)?.id
                ?: error("Expected partner queue entry"),
            today = now.toLocalDate(),
            exclusionPolicy = matchmakingPairEligibilityService.effectiveExclusionPolicy(),
            previousPairingCutoff = null,
            firstChatExpirationCutoff = null,
            firstChatDecisionMismatchCutoff = null
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

    private fun countMatchesBetween(userAId: UUID, userBId: UUID): Int =
        matchRepository.findAll().count {
            (it.userAId == userAId && it.userBId == userBId) ||
                (it.userAId == userBId && it.userBId == userAId)
        }
}
