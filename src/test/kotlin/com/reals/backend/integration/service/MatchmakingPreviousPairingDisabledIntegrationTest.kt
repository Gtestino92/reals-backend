package com.reals.backend.integration.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.domain.Connection
import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.domain.VisualReview
import com.reals.backend.integration.BaseIT
import com.reals.backend.repository.matching.MatchmakingPairBlockingReason
import com.reals.backend.repository.matching.MatchmakingPairExclusionPolicy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime
import java.util.UUID

@TestPropertySource(
    properties = [
        "matchmaking.allow-active-pair-duplicates=false",
        "matchmaking.exclude-previous-pairing=false"
    ]
)
class MatchmakingPreviousPairingDisabledIntegrationTest : BaseIT() {

    @Test
    fun `historical terminal outcomes are eligible when previous pairing exclusion is disabled`() {
        val now = fixedNow()
        val chatRejected = createQueuedCompatiblePair("disabled-chat-rejected")
        saveHistoricalMatch(chatRejected.first, chatRejected.second, MatchState.CHAT_REJECTED, now.minusDays(1))
        val visualRejected = createQueuedCompatiblePair("disabled-visual-rejected")
        saveHistoricalMatch(visualRejected.first, visualRejected.second, MatchState.VISUAL_REJECTED, now.minusDays(1))
        val firstChatExpired = createQueuedCompatiblePair("disabled-first-chat-expired")
        saveExpiredFirstChatMatch(firstChatExpired.first, firstChatExpired.second, now.minusDays(1))
        val visualExpired = createQueuedCompatiblePair("disabled-visual-expired")
        saveExpiredVisualReviewMatch(visualExpired.first, visualExpired.second, now.minusDays(1))
        val closedConnection = createQueuedCompatiblePair("disabled-closed-connection")
        saveConnection(closedConnection.first, closedConnection.second, ConnectionState.CLOSED, now.minusDays(1))

        listOf(chatRejected, visualRejected, firstChatExpired, visualExpired, closedConnection).forEach { (userA, userB) ->
            assertTrue(matchmakingPairEligibilityService.isPairEligible(userA, userB, now))
            assertTrue(queryContainsPair(userA, userB, now))
            assertEquals(null, activeOnlyBlockingReason(userA, userB))
        }
    }

    @Test
    fun `active pairs and blocks remain excluded when previous pairing exclusion is disabled`() {
        val now = fixedNow()
        val activeMatch = createQueuedCompatiblePair("disabled-active-match")
        saveHistoricalMatch(activeMatch.first, activeMatch.second, MatchState.CHAT_ACTIVE, now)
        val activeConnection = createQueuedCompatiblePair("disabled-active-connection")
        saveConnection(activeConnection.first, activeConnection.second, ConnectionState.SECOND_CHAT, now)
        val blocked = createQueuedCompatiblePair("disabled-blocked")
        userBlockService.blockUser(
            blockerUserId = blocked.second,
            blockedUserId = blocked.first,
            source = UserBlockSource.SAFETY_REPORT,
            sourceReportId = UUID.randomUUID()
        )

        listOf(activeMatch, activeConnection, blocked).forEach { (userA, userB) ->
            assertFalse(matchmakingPairEligibilityService.isPairEligible(userA, userB, now))
            assertFalse(queryContainsPair(userA, userB, now))
        }
        assertEquals(MatchmakingPairBlockingReason.ACTIVE_INTERACTION, activeOnlyBlockingReason(activeMatch.first, activeMatch.second))
        assertEquals(MatchmakingPairBlockingReason.ACTIVE_INTERACTION, activeOnlyBlockingReason(activeConnection.first, activeConnection.second))
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
        val userAQueueEntry = matchmakingQueueRepository.findByUserId(userAId)
        val userBQueueEntry = matchmakingQueueRepository.findByUserId(userBId)

        return listOfNotNull(userAQueueEntry, userBQueueEntry).any { anchor ->
            matchmakingCandidateRepository.findEligiblePartnerCandidates(
                anchorQueueEntryId = anchor.id,
                limit = 100,
                today = now.toLocalDate(),
                exclusionPolicy = MatchmakingPairExclusionPolicy.ACTIVE_ONLY,
                previousPairingCutoff = null,
                firstChatExpirationCutoff = null
            ).any {
                (it.pair.userAId == userAId && it.pair.userBId == userBId) ||
                    (it.pair.userAId == userBId && it.pair.userBId == userAId)
            }
        }
    }

    private fun activeOnlyBlockingReason(
        userAId: UUID,
        userBId: UUID
    ): MatchmakingPairBlockingReason? =
        matchmakingPairEligibilityRepository.findBlockingReason(
            userAId = userAId,
            userBId = userBId,
            exclusionPolicy = MatchmakingPairExclusionPolicy.ACTIVE_ONLY,
            previousPairingCutoff = null,
            firstChatExpirationCutoff = null
        )

    private fun saveHistoricalMatch(
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

    private fun saveExpiredFirstChatMatch(
        userAId: UUID,
        userBId: UUID,
        updatedAt: OffsetDateTime
    ): Match {
        val match = saveHistoricalMatch(userAId, userBId, MatchState.EXPIRED, updatedAt)
        chatRepository.saveAndFlush(
            Chat(
                matchId = match.id,
                chatType = ChatType.FIRST_CHAT,
                status = ChatStatus.EXPIRED,
                startedAt = updatedAt.minusMinutes(16),
                timeoutAt = updatedAt.minusMinutes(1),
                endedAt = updatedAt,
                endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
            )
        )
        return match
    }

    private fun saveExpiredVisualReviewMatch(
        userAId: UUID,
        userBId: UUID,
        updatedAt: OffsetDateTime
    ): Match {
        val match = saveHistoricalMatch(userAId, userBId, MatchState.EXPIRED, updatedAt)
        visualReviewRepository.saveAndFlush(
            VisualReview(
                matchId = match.id,
                expiresAt = updatedAt.minusSeconds(1),
                createdAt = updatedAt.minusDays(1),
                updatedAt = updatedAt
            )
        )
        return match
    }

    private fun saveConnection(
        userAId: UUID,
        userBId: UUID,
        state: ConnectionState,
        updatedAt: OffsetDateTime
    ): Connection {
        val match = saveHistoricalMatch(userAId, userBId, MatchState.VISUAL_APPROVED, updatedAt.minusDays(60))
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
